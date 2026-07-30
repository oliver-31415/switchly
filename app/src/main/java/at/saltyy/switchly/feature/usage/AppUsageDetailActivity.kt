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

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AttemptLimitStore
import at.saltyy.switchly.data.prefs.LimitReachedStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageLimitResetStore
import at.saltyy.switchly.data.prefs.UsageLimitSessionRuntimeStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.databinding.ActivityStatisticsAppUsageDetailBinding
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showSwitchlyOptionDialog
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.AppBlockSafety
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppUsageDetailActivity : AppCompatActivity() {

    private fun toolbarIconColor(): Int {
        return if (MaterialColors.isColorLight(AccentColor.getToolbarColor(this))) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }

    private fun setupInfoAction() {
        b.toolbar.menu.clear()
        b.toolbar.menu.add(R.string.usage_details_info_title).apply {
            setIcon(R.drawable.info_24)
            icon?.mutate()?.setTint(toolbarIconColor())
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener {
                showUsageDetailsInfo()
                true
            }
        }
    }

    private fun showUsageDetailsInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.usage_details_info_title)
            .setMessage(R.string.usage_details_info_body)
            .setPositiveButton(android.R.string.ok, null)
            .showAccented()
    }

    private fun syncLimitEditingUi() {
        val locked = SwitchModeStore.isEnabled(this)
        b.cardLimitTime.isEnabled = !locked
        b.cardLimitAttempts.isEnabled = !locked
        b.btnEditLimits.isEnabled = !locked
        b.cardLimitTime.alpha = if (locked) 0.62f else 1f
        b.cardLimitAttempts.alpha = if (locked) 0.62f else 1f
        b.btnEditLimits.alpha = if (locked) 0.62f else 1f
    }

    private lateinit var b: ActivityStatisticsAppUsageDetailBinding

    private var currentRange: Range = Range.TODAY
    private var currentSeries: List<Long> = emptyList()
    private var currentXAxisLabels: List<String> = emptyList()
    private var rangeJob: Job? = null
    private var customRangeStartMillis: Long? = null
    private var customRangeEndMillis: Long? = null
    private var customRangePickerShowing = false

    private enum class Range { TODAY, WEEK, MONTH, YEAR, CUSTOM }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        b = ActivityStatisticsAppUsageDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        b.toolbar.navigationIcon?.mutate()?.setTint(toolbarIconColor())
        b.toolbar.setNavigationOnClickListener { finish() }
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = b.toolbar)
        setupInfoAction()
        b.tvLimitHint.visibility = View.GONE

        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: pkg

        b.toolbar.title = getString(R.string.app_usage_detail_title)
        b.detailTitle.text = label
        b.detailSubtitle.text = getString(R.string.app_usage_detail_subtitle)
        updateProfileSubtitle()

        b.btnRangeYear.visibility = View.VISIBLE
        b.btnRangeCustom.visibility = View.VISIBLE
        b.sessions.visibility = View.GONE
        b.attempts.visibility = View.GONE
        b.cardLaunchStats.visibility = View.GONE
        b.cardUsageTimeline.visibility = View.GONE

        val today = UsageStore.getUsageMsToday(this, pkg)

        // icon/name
        val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
        b.icon.setImageDrawable(icon)

        b.weekTotal.visibility = View.GONE
        b.todayUsage.text = getString(
            R.string.usage_kv_fmt,
            getString(R.string.usage_today),
            StatsFormat.prettyMsWithSeconds(today)
        )

        // Limits (edited via the single "tune" icon)
        refreshDailyLimit(pkg)
        refreshAttemptLimit(pkg)
        refreshSessionLimitGraph(pkg)
        syncLimitEditingUi()

        // Make it clear that limits are profile-bound.
        b.tvProfileIndicator.text = ProfileStore.getCurrent(this)?.let {
            getString(R.string.profile_active_fmt, it)
        }.orEmpty()
        b.tvProfileIndicator.visibility = View.GONE

        // Direct editing: tapping a limit card opens the correct editor.
        b.cardLimitTime.setOnClickListener {
            QuickLimitDialogs.showForApp(
                activity = this,
                pkg = pkg,
                label = label,
                startOnAttempts = false
            ) {
                refreshDailyLimit(pkg)
                refreshAttemptLimit(pkg)
                refreshSessionLimitGraph(pkg)
            }
        }
        b.cardLimitAttempts.setOnClickListener {
            QuickLimitDialogs.showForApp(
                activity = this,
                pkg = pkg,
                label = label,
                startOnAttempts = true
            ) {
                refreshDailyLimit(pkg)
                refreshAttemptLimit(pkg)
                refreshSessionLimitGraph(pkg)
            }
        }

        b.btnEditLimits.setOnClickListener {
            QuickLimitDialogs.showForApp(
                activity = this,
                pkg = pkg,
                label = label
            ) {
                refreshDailyLimit(pkg)
                refreshAttemptLimit(pkg)
                refreshSessionLimitGraph(pkg)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SwitchModeStore.enabledFlow.collect {
                    runOnUiThread { syncLimitEditingUi() }
                }
            }
        }

        val initialCustomStart = intent.getLongExtra(EXTRA_INITIAL_START_MS, -1L)
        val initialCustomEnd = intent.getLongExtra(EXTRA_INITIAL_END_MS, -1L)
        if (initialCustomStart > 0L && initialCustomEnd >= initialCustomStart) {
            customRangeStartMillis = initialCustomStart
            customRangeEndMillis = initialCustomEnd
        }

        // If opened from a specific Stats range, default the chart to the most relevant view.
        val requestedInitialRange = when (intent.getStringExtra(EXTRA_INITIAL_RANGE)) {
            RANGE_TODAY -> Range.TODAY
            RANGE_MONTH -> Range.MONTH
            RANGE_YEAR -> Range.YEAR
            RANGE_CUSTOM -> Range.CUSTOM
            else -> Range.WEEK
        }
        val initialRange = if (ensureRangeAllowed(requestedInitialRange, showGate = false)) {
            requestedInitialRange
        } else {
            Range.TODAY
        }
        setWeekdayLabels()
        configureRangeFilterButtons()
        b.toggleRange.check(when (initialRange) {
            Range.TODAY -> b.btnRangeToday.id
            Range.MONTH -> b.btnRangeMonth.id
            Range.YEAR -> b.btnRangeYear.id
            Range.CUSTOM -> b.btnRangeCustom.id
            else -> b.btnRangeWeek.id
        })
        setupChartInteractions(label)
        b.toggleRange.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == b.btnRangeCustom.id) {
                showCustomRangePicker(pkg)
                return@addOnButtonCheckedListener
            }
            val range = when (checkedId) {
                b.btnRangeToday.id -> Range.TODAY
                b.btnRangeMonth.id -> Range.MONTH
                b.btnRangeYear.id -> Range.YEAR
                else -> Range.WEEK
            }
            if (!ensureRangeAllowed(range)) {
                b.toggleRange.check(chipIdForRange(currentRange))
                return@addOnButtonCheckedListener
            }
            applyRange(pkg, range)
        }
        b.btnClearCustomRange.setOnClickListener {
            customRangeStartMillis = null
            customRangeEndMillis = null
            b.toggleRange.check(b.btnRangeToday.id)
            applyRange(pkg, Range.TODAY)
        }

        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) { UsageHistoryBackfill.maybeRun(this@AppUsageDetailActivity) }
            if (changed) {
                val refreshedToday = UsageStore.getUsageMsToday(this@AppUsageDetailActivity, pkg)
                b.todayUsage.text = getString(
                    R.string.usage_kv_fmt,
                    getString(R.string.usage_today),
                    StatsFormat.prettyMsWithSeconds(refreshedToday)
                )
            }
            applyRange(pkg, initialRange)
        }
    }

    override fun onResume() {
        super.onResume()
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        updateProfileSubtitle()
        b.tvProfileIndicator.visibility = View.GONE
        refreshDailyLimit(pkg)
        refreshAttemptLimit(pkg)
        syncLimitEditingUi()
    }

    private fun setHeaderUsageTotal(labelRes: Int, totalMs: Long) {
        b.todayUsage.text = getString(
            R.string.usage_kv_fmt,
            getString(labelRes),
            StatsFormat.prettyMsWithSeconds(totalMs)
        )
    }

    private fun applyRange(pkg: String, range: Range) {
        rangeJob?.cancel()
        currentRange = range
        updateCustomRangeSummary()
        when (range) {
            Range.TODAY -> {
                currentSeries = UsageSanity.capSeriesToRange(this, UsageStatsRepo.getTodayPerHour(this, pkg), UsageSanity.RangeCap.TODAY)
                currentXAxisLabels = buildTodayHourLabels(currentSeries.size)
                b.chart.visibility = View.GONE
                b.weekdayRow.visibility = View.GONE
                b.lineChart.visibility = View.VISIBLE
                b.lineChart.setValues(currentSeries)
                b.lineChart.setXAxisLabels(currentXAxisLabels)
                val total = UsageSanity.capTotalToRange(this, currentSeries.sum(), UsageSanity.RangeCap.TODAY)
                setHeaderUsageTotal(R.string.usage_today, total)
            }

            Range.WEEK -> {
                currentXAxisLabels = emptyList()
                val perDay = UsageSanity.capSeriesToRange(this, UsageStore.getUsageMsSeriesForLastNDays(this, pkg, 7), UsageSanity.RangeCap.WEEK)
                currentSeries = perDay
                b.chart.visibility = View.VISIBLE
                b.weekdayRow.visibility = View.VISIBLE
                b.lineChart.visibility = View.GONE
                b.chart.setValues(perDay)
                b.lineChart.setXAxisLabels(emptyList())

                val total = UsageSanity.capTotalToRange(this, perDay.sum(), UsageSanity.RangeCap.WEEK)
                setHeaderUsageTotal(R.string.usage_week_total, total)
            }

            Range.MONTH -> {
                currentXAxisLabels = buildDayIndexLabels(daysSinceStartOfMonth())
                val perDay = UsageSanity.capSeriesToRange(this, UsageStore.getUsageMsSeriesForCurrentMonth(this, pkg), UsageSanity.RangeCap.MONTH)
                currentSeries = perDay
                b.chart.visibility = View.GONE
                b.weekdayRow.visibility = View.GONE
                b.lineChart.visibility = View.VISIBLE
                b.lineChart.setValues(perDay)
                b.lineChart.setXAxisLabels(buildDayIndexLabels(perDay.size))

                val total = UsageSanity.capTotalToRange(this, perDay.sum(), UsageSanity.RangeCap.MONTH)
                setHeaderUsageTotal(R.string.usage_month_total, total)
            }

            Range.YEAR -> {
                b.chart.visibility = View.GONE
                b.weekdayRow.visibility = View.GONE
                b.lineChart.visibility = View.VISIBLE
                rangeJob = lifecycleScope.launch {
                    val buckets = withContext(Dispatchers.IO) { UsageStore.getUsageMsMonthBucketsForCurrentYear(this@AppUsageDetailActivity, pkg) }
                    if (currentRange != Range.YEAR) return@launch
                    currentSeries = UsageSanity.capSeriesToRange(this@AppUsageDetailActivity, buckets.map { it.totalMs }, UsageSanity.RangeCap.YEAR)
                    currentXAxisLabels = buckets.map { monthLabel(it.month1Based, it.year) }
                    b.lineChart.setValues(currentSeries)
                    b.lineChart.setXAxisLabels(currentXAxisLabels)
                    setHeaderUsageTotal(R.string.usage_year_total, UsageSanity.capTotalToRange(this@AppUsageDetailActivity, currentSeries.sum(), UsageSanity.RangeCap.YEAR))
                }
            }

            Range.CUSTOM -> {
                b.chart.visibility = View.GONE
                b.weekdayRow.visibility = View.GONE
                b.lineChart.visibility = View.VISIBLE
                val start = customRangeStartMillis ?: startOfTodayMillis()
                val end = customRangeEndMillis ?: System.currentTimeMillis()
                currentSeries = UsageStore.getUsageMsSeriesForDateRange(this, pkg, start, end)
                currentXAxisLabels = buildCustomDateLabels(start, currentSeries.size)
                b.lineChart.setValues(currentSeries)
                b.lineChart.setXAxisLabels(currentXAxisLabels)
                setHeaderUsageTotal(R.string.activity_history_range_custom, currentSeries.sum())
            }
        }
    }

    private fun configureRangeFilterButtons() {
        listOf(b.btnRangeToday, b.btnRangeWeek, b.btnRangeMonth, b.btnRangeYear).forEach { button ->
            configureRangeButton(button, custom = false)
        }
        configureRangeButton(b.btnRangeCustom, custom = true)
    }

    private fun configureRangeButton(button: MaterialButton, custom: Boolean) {
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = dp(40)
        button.minimumHeight = dp(40)
        button.insetTop = 0
        button.insetBottom = 0
        button.cornerRadius = dp(4)
        button.iconPadding = 0
        if (custom) {
            button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        button.gravity = android.view.Gravity.CENTER
        button.setAllCaps(false)
        button.setPadding(if (custom) dp(8) else dp(4), 0, if (custom) dp(8) else dp(4), 0)
        button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (custom) dp(44) else 0
            height = dp(40)
            weight = if (custom) 0f else 1f
        }
    }

    private fun updateProfileSubtitle() {
        b.toolbar.subtitle = ProfileStore.getCurrent(this)?.let {
            getString(R.string.profile_active_fmt, it)
        }
    }

    private fun updateCustomRangeSummary() {
        val start = customRangeStartMillis
        val end = customRangeEndMillis
        if (currentRange != Range.CUSTOM || start == null || end == null) {
            b.customRangeSummary.visibility = View.GONE
            return
        }
        b.customRangeSummary.visibility = View.VISIBLE
        val fmt = DateFormat.getDateInstance(DateFormat.SHORT)
        b.customRangeValue.text = getString(
            R.string.activity_history_range_custom_value,
            fmt.format(Date(start)),
            fmt.format(Date(end))
        )
    }

    private fun ensureRangeAllowed(range: Range, showGate: Boolean = true): Boolean {
        if (range == Range.TODAY || StatsPremiumGate.canUseExtendedStats(this)) {
            return true
        }
        if (showGate) StatsPremiumGate.show(this)
        return false
    }

    private fun chipIdForRange(range: Range): Int {
        return when (range) {
            Range.TODAY -> b.btnRangeToday.id
            Range.WEEK -> b.btnRangeWeek.id
            Range.MONTH -> b.btnRangeMonth.id
            Range.YEAR -> b.btnRangeYear.id
            Range.CUSTOM -> b.btnRangeCustom.id
        }
    }

    private fun showCustomRangePicker(pkg: String) {
        if (!ensureRangeAllowed(Range.CUSTOM)) {
            b.toggleRange.check(chipIdForRange(currentRange))
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
            b.toggleRange.check(b.btnRangeCustom.id)
            applyRange(pkg, Range.CUSTOM)
        }
        picker.addOnDismissListener { customRangePickerShowing = false }
        runCatching { picker.show(supportFragmentManager, "app_usage_detail_custom_range") }
            .onSuccess { UsageDatePickerAccentTint.apply(this, picker) }
            .onFailure { customRangePickerShowing = false }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun monthLabel(month1Based: Int, year: Int, shortYear: Boolean = false): String {
        val monthName = DateFormatSymbols.getInstance().shortMonths.getOrNull((month1Based - 1).coerceIn(0, 11)).orEmpty().trim().ifBlank { month1Based.toString() }
        return if (shortYear) {
            "$monthName ${year % 100}"
        } else {
            monthName
        }
    }

    private fun buildDayIndexLabels(count: Int): List<String> {
        // For "month" range we show the current month-to-date; the series starts on day 1.
        return (1..count.coerceAtLeast(0)).map { it.toString() }
    }

    private fun buildThisYearMonthLabels(count: Int): List<String> {
        val dfs = DateFormatSymbols.getInstance()
        val months = dfs.shortMonths
        // Series is Jan..currentMonth, so index 0 = Jan.
        return (0 until count.coerceAtLeast(0)).map { idx ->
            months.getOrNull(idx).orEmpty().trim().ifBlank { (idx + 1).toString() }
        }
    }

    private fun buildLastNMonthLabels(count: Int): List<String> {
        if (count <= 0) {
            return emptyList()
        }
        val fmt = SimpleDateFormat("MMM yy", Locale.getDefault())
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, -(count - 1))
        }
        return buildList {
            repeat(count) {
                add(fmt.format(cal.time))
                cal.add(Calendar.MONTH, 1)
            }
        }
    }

    private fun daysSinceStartOfMonth(): Int {
        val c = Calendar.getInstance()
        // Day of month is 1-based and already represents "days since the 1st" including today.
        return c.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1).coerceAtMost(31)
    }

    private fun getThisYearMonthsTotals(pkg: String): List<Long> {
        val now = System.currentTimeMillis()
        val cur = Calendar.getInstance().apply { timeInMillis = now }
        val year = cur.get(Calendar.YEAR)
        val curMonth = cur.get(Calendar.MONTH)

        // Use the app's stored per-day usage as the primary source for long windows.
        // Summing historical UsageStats day-windows can massively overcount on some devices, while the local daily store stays stable and monotonic.
        val totals = ArrayList<Long>(curMonth + 1)
        for (m in Calendar.JANUARY..curMonth) {
            val fromStore = UsageStore.getUsageMsForMonth(this, pkg, year, m + 1)
            val monthTotal = if (m == curMonth && fromStore <= 0L) {
                // For the current month, fall back to the same per-day series the Month screen uses.
                UsageStatsRepo.getLastNDaysPerDay(this, pkg, daysSinceStartOfMonth()).sum()
            } else {
                fromStore
            }
            totals.add(monthTotal.coerceAtLeast(0L))
        }
        return totals
    }

    private fun getLast12MonthsTotals(pkg: String): List<Long> {
        val now = System.currentTimeMillis()
        val base = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val out = ArrayList<Long>(12)
        for (i in 11 downTo 0) {
            val startCal = (base.clone() as Calendar).apply { add(Calendar.MONTH, -i) }
            val endCal = (startCal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            val start = startCal.timeInMillis
            val end = if (i == 0) now else endCal.timeInMillis
            out.add(UsageStatsRepo.getTotalMsForWindow(this, start, end, pkg))
        }
        return out
    }

    private fun setupChartInteractions(label: String) {
        // Tap bars/points to see exact numbers.
        b.chart.setOnBarSelectedListener { index, valueMs ->
            if (currentRange != Range.WEEK) return@setOnBarSelectedListener
            showPointDialog(label, currentRange, index, valueMs, currentSeries)
        }

        b.lineChart.setOnPointSelectedListener { index, valueMs ->
            if (currentRange == Range.WEEK) return@setOnPointSelectedListener
            showPointDialog(label, currentRange, index, valueMs, currentSeries)
        }
    }

    private fun showPointDialog(label: String, range: Range, index: Int, valueMs: Long, series: List<Long>) {
        val total = series.sum().coerceAtLeast(0L)
        val pct = if (total > 0L) (valueMs.toDouble() * 100.0/total.toDouble()) else 0.0

        val whenLabel = when (range) {
            Range.TODAY -> currentXAxisLabels.getOrNull(index) ?: formatHourLabel(index)
            Range.YEAR -> currentXAxisLabels.getOrNull(index) ?: formatMonthLabel(index, series.size)
            Range.CUSTOM -> currentXAxisLabels.getOrNull(index) ?: formatDayLabel(index, series.size)
            else -> formatDayLabel(index, series.size)
        }

        val msg = buildString {
            append(getString(R.string.usage_kv_fmt, getString(R.string.usage_value_label), StatsFormat.prettyMsWithSeconds(valueMs)))
            if (total > 0L) {
                append("\n")
                append(getString(R.string.usage_kv_fmt, getString(R.string.usage_share_of_period), String.format(Locale.getDefault(), "%.1f%%", pct)))
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.usage_point_dialog_title, label, whenLabel))
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun buildTodayHourLabels(count: Int): List<String> {
        return (0 until count.coerceAtLeast(0)).map { hour ->
            String.format(Locale.getDefault(), "%02d", hour)
        }
    }

    private fun formatHourLabel(index: Int): String {
        val startHour = index.coerceIn(0, 23)
        val endHour = (startHour + 1).coerceAtMost(24)
        return String.format(Locale.getDefault(), "%02d:00–%02d:00", startHour, endHour)
    }

    private fun formatDayLabel(index: Int, size: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, index - (size - 1))
        val fmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        return fmt.format(cal.time)
    }

    private fun formatMonthLabel(index: Int, size: Int): String {
        val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        cal.add(Calendar.MONTH, index - (size - 1))
        val fmt = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        return fmt.format(cal.time)
    }

    private fun buildCustomDateLabels(startMs: Long, count: Int): List<String> {
        val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val cal = Calendar.getInstance().apply { timeInMillis = startMs }
        return (0 until count.coerceAtLeast(0)).map {
            fmt.format(cal.time).also { cal.add(Calendar.DAY_OF_YEAR, 1) }
        }
    }

    private fun startOfTodayMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun localDayToDatePickerUtcMillis(localMillis: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = localMillis }
        return Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private fun datePickerUtcMillisToLocalDayStart(utcMillis: Long): Long {
        val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun datePickerUtcMillisToLocalDayEnd(utcMillis: Long): Long {
        val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    private fun refreshDailyLimit(pkg: String) {
        val profile = ProfileStore.getCurrent(this)
        if (profile.isNullOrBlank()) {
            b.tvDailyLimitValue.text = getString(R.string.select_profile_first)
            b.btnEditLimits.isEnabled = false
            return
        }
        b.btnEditLimits.isEnabled = true

        val limitMin = UsageLimitStore.getLimitMinutes(this, profile, pkg)
        b.tvDailyLimitValue.text =
            if (limitMin <= 0) getString(R.string.no_limit)
            else {
                val resetMode = UsageLimitResetStore.getMode(this, profile, pkg)
                getString(
                    if (resetMode == UsageLimitResetStore.MODE_SESSION) R.string.session_reset_limit_value_format else R.string.daily_limit_value_format,
                    limitMin
                )
            }
        refreshSessionLimitGraph(pkg)
    }

    private fun refreshSessionLimitGraph(pkg: String) {
        val profile = ProfileStore.getCurrent(this)
        if (profile.isNullOrBlank()) {
            b.sessionLimitContainer.visibility = View.GONE
            return
        }

        val limitMin = UsageLimitStore.getLimitMinutes(this, profile, pkg)
        val resetMode = UsageLimitResetStore.getMode(this, profile, pkg)
        if (limitMin <= 0 || resetMode != UsageLimitResetStore.MODE_SESSION) {
            b.sessionLimitContainer.visibility = View.GONE
            return
        }

        val state = UsageLimitSessionRuntimeStore.get(this, profile, pkg)
        val limitMs = state?.limitMs ?: TimeUnit.MINUTES.toMillis(limitMin.toLong())
        val usedMs = state?.usedMs ?: 0L
        val remainingMs = state?.remainingMs ?: limitMs

        b.sessionLimitContainer.visibility = View.VISIBLE
        b.sessionLimitSummary.text = getString(
            R.string.session_limit_usage_summary,
            StatsFormat.prettyMsWithSeconds(usedMs),
            StatsFormat.prettyMsWithSeconds(limitMs),
            StatsFormat.prettyMsWithSeconds(remainingMs)
        )
        b.sessionLimitProgress.progress =
            if (limitMs <= 0L) 0 else ((usedMs * 100L) / limitMs).toInt().coerceIn(0, 100)
        b.sessionLimitProgress.progressTintList = ColorStateList.valueOf(AccentColor.getAccentColorInt(this))
    }

    private fun refreshAttemptLimit(pkg: String) {
        val profile = ProfileStore.getCurrent(this)
        if (profile.isNullOrBlank()) {
            b.tvDailyAttemptLimitValue.text = getString(R.string.select_profile_first)
            b.btnEditLimits.isEnabled = false
            return
        }
        b.btnEditLimits.isEnabled = true

        val limit = AttemptLimitStore.getLimitAttempts(this, profile, pkg)
        b.tvDailyAttemptLimitValue.text =
            if (limit <= 0) getString(R.string.no_limit)
            else resources.getQuantityString(R.plurals.daily_attempt_limit_value_format, limit, limit)
    }

    private fun ensureAppCanBeManaged(pkg: String, proceed: () -> Unit) {
        val safety = AppBlockSafety.resolve(this, pkg)
        when (safety.level) {
            AppBlockSafety.Level.PROTECTED -> {
                AlertDialog.Builder(this)
                    .setTitle(safety.warningTitle ?: getString(R.string.app_picker_protected_warning_title))
                    .setMessage(safety.warningMessage ?: getString(R.string.app_picker_protected_warning_message))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.app_picker_block_protected_confirm) { _, _ -> proceed() }
                    .showAccented()
            }
            AppBlockSafety.Level.SOFT_WARNING -> {
                AlertDialog.Builder(this)
                    .setTitle(safety.warningTitle ?: getString(R.string.app_picker_protected_caution_title))
                    .setMessage(safety.warningMessage ?: safety.hint ?: getString(R.string.app_picker_protected_generic_hint))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.continue_label) { _, _ -> proceed() }
                    .showAccented()
            }
            else -> proceed()
        }
    }

    private fun showDailyLimitDialog(pkg: String, label: String) {
        ensureAppCanBeManaged(pkg) {
        val profile = ProfileStore.getCurrent(this) ?: return@ensureAppCanBeManaged

        val current = UsageLimitStore.getLimitMinutes(this, profile, pkg)
        val presets = listOf(0, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120)
        val items = presets.map {
            if (it == 0) getString(R.string.no_limit) else resources.getQuantityString(R.plurals.minutes_format, it, it)
        } + getString(R.string.custom_minutes)

        showSwitchlyOptionDialog(
            title = getString(R.string.set_daily_limit_title, label),
            options = items.mapIndexed { index, item ->
                SwitchlyDialogOption(
                    title = item,
                    iconRes = if (index == items.lastIndex) R.drawable.edit_24 else R.drawable.alarm_24,
                    selected = if (index < presets.size) {
                        presets[index] == current
                    } else {
                        current !in presets
                    }
                )
            },
            confirmSelection = true
        ) { which ->
            if (which == items.lastIndex) {
                showCustomMinutesInput(pkg, label)
                return@showSwitchlyOptionDialog
            }

            val chosen = presets.getOrNull(which) ?: 0
            applyDailyLimit(profile, pkg, chosen)
        }
        }
    }

    private fun showCustomMinutesInput(pkg: String, label: String) {
        val profile = ProfileStore.getCurrent(this) ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.minutes_hint)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.custom_minutes_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val m = input.text?.toString()?.trim()?.toIntOrNull()
                if (m == null || m < 0) {
                    Toast.makeText(this, R.string.invalid_value, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                applyDailyLimit(profile, pkg, m)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
    }

    private fun applyDailyLimit(profile: String, pkg: String, minutes: Int) {
        if (SwitchModeStore.isEnabled(this)) {
            Toast.makeText(this, R.string.toast_disable_switchly_to_edit_app_limits, Toast.LENGTH_SHORT).show()
            return
        }

        val m = minutes.coerceAtLeast(0)
        UsageLimitStore.setLimitMinutes(this, profile, pkg, m)

        // If a limit changes, ensure we don't keep an old "reached" flag around.
        LimitReachedStore.clearToday(this, pkg)

        // If the limit is set, ensure the app is "managed" for the current profile.
        if (m > 0) {
            val selected = ProfileStore.getSelectedForProfileMode(this, profile).toMutableSet()
            if (!selected.contains(pkg)) {
                selected.add(pkg)
                ProfileStore.setSelectedForProfileMode(this, profile, selected)
            }
        }

        // Reset today's usage if user clears the limit (common expectation).
        if (m == 0) {
            UsageStore.setUsageMsToday(this, pkg, 0L)
        }

        BlockingRuntime.ensureRunning(this)
        refreshDailyLimit(pkg)
    }

    private fun setWeekdayLabels() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(6)
        val dfs = DateFormatSymbols.getInstance()
        val views = listOf(b.day1, b.day2, b.day3, b.day4, b.day5, b.day6, b.day7)
        for (i in 0 until 7) {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val name = dfs.shortWeekdays.getOrNull(dow).orEmpty().trim()
            views[i].text = name
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
        const val EXTRA_INITIAL_RANGE = "initial_range"
        const val RANGE_TODAY = "today"
        const val RANGE_WEEK = "week"
        const val RANGE_MONTH = "month"
        const val RANGE_YEAR = "year"
        const val RANGE_CUSTOM = "custom"
        const val EXTRA_INITIAL_START_MS = "initial_start_ms"
        const val EXTRA_INITIAL_END_MS = "initial_end_ms"
    }
}
