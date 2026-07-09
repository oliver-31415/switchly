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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AttemptLimitStore
import at.saltyy.switchly.data.prefs.BlockAttemptStore
import at.saltyy.switchly.data.prefs.LimitReachedStore
import at.saltyy.switchly.data.prefs.OpenCountStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageLimitResetStore
import at.saltyy.switchly.data.prefs.UsageLimitSessionRuntimeStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.databinding.ActivityScreenTimeDetailBinding
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showSwitchlyOptionDialog
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.AppBlockSafety
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenTimeDetailActivity : AppCompatActivity() {

    private fun toolbarIconColor(): Int {
        return if (MaterialColors.isColorLight(AccentColor.getToolbarColor(this))) Color.BLACK else Color.WHITE
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

    private lateinit var b: ActivityScreenTimeDetailBinding

    private var currentRange: Range = Range.TODAY
    private var currentSeries: List<Long> = emptyList()
    private var currentXAxisLabels: List<String> = emptyList()
    private var rangeJob: Job? = null

    private data class CountSummary(
        val sessionsLabelRes: Int,
        val attemptsLabelRes: Int,
        val sessionsCount: Int,
        val attemptsCount: Int
    )

    private enum class Range { TODAY, WEEK, MONTH, YEAR, OVERALL }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        b = ActivityScreenTimeDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        b.toolbar.navigationIcon?.mutate()?.setTint(toolbarIconColor())
        b.toolbar.setNavigationOnClickListener { finish() }
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = b.toolbar)
        setupInfoAction()
        b.tvLimitHint.visibility = View.GONE

        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: pkg

        b.toolbar.title = label

        b.btnRangeYear.visibility = View.VISIBLE
        b.btnRangeOverall.visibility = View.VISIBLE

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

        // Range selector (Week/Month/Year)

        // If opened from a specific Stats range, default the chart to the most relevant view.
        val initialRange = when (intent.getStringExtra(EXTRA_INITIAL_RANGE)) {
            RANGE_TODAY -> Range.TODAY
            RANGE_MONTH -> Range.MONTH
            RANGE_YEAR -> Range.YEAR
            RANGE_OVERALL -> Range.OVERALL
            else -> Range.WEEK
        }
        setWeekdayLabels()
        b.toggleRange.check(when (initialRange) {
            Range.TODAY -> b.btnRangeToday.id
            Range.MONTH -> b.btnRangeMonth.id
            Range.YEAR -> b.btnRangeYear.id
            Range.OVERALL -> b.btnRangeOverall.id
            else -> b.btnRangeWeek.id
        })
        setupChartInteractions(label)
        updateCountSummaryForRange(pkg, initialRange)

        b.toggleRange.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val range = when (checkedId) {
                b.btnRangeToday.id -> Range.TODAY
                b.btnRangeMonth.id -> Range.MONTH
                b.btnRangeYear.id -> Range.YEAR
                b.btnRangeOverall.id -> Range.OVERALL
                else -> Range.WEEK
            }
            applyRange(pkg, range)
        }

        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) { UsageHistoryBackfill.maybeRun(this@ScreenTimeDetailActivity) }
            if (changed) {
                val refreshedToday = UsageStore.getUsageMsToday(this@ScreenTimeDetailActivity, pkg)
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
        b.tvProfileIndicator.text = ProfileStore.getCurrent(this)?.let {
            getString(R.string.profile_active_fmt, it)
        }.orEmpty()
        updateCountSummaryForRange(pkg, currentRange)
        refreshDailyLimit(pkg)
        refreshAttemptLimit(pkg)
        syncLimitEditingUi()
    }

    private fun updateCountSummaryForRange(pkg: String, range: Range) {
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                when (range) {
                    Range.TODAY -> CountSummary(
                        sessionsLabelRes = R.string.usage_sessions_today,
                        attemptsLabelRes = R.string.usage_attempts_today,
                        sessionsCount = OpenCountStore.getTodayAllProfiles(this@ScreenTimeDetailActivity, pkg),
                        attemptsCount = BlockAttemptStore.getToday(this@ScreenTimeDetailActivity, pkg)
                    )
                    Range.WEEK -> CountSummary(
                        sessionsLabelRes = R.string.usage_sessions_week,
                        attemptsLabelRes = R.string.usage_attempts_week,
                        sessionsCount = OpenCountStore.getForCurrentWeekAllProfiles(this@ScreenTimeDetailActivity, pkg),
                        attemptsCount = BlockAttemptStore.getForCurrentWeek(this@ScreenTimeDetailActivity, pkg)
                    )
                    Range.MONTH -> CountSummary(
                        sessionsLabelRes = R.string.usage_sessions_month,
                        attemptsLabelRes = R.string.usage_attempts_month,
                        sessionsCount = OpenCountStore.getForCurrentMonthAllProfiles(this@ScreenTimeDetailActivity, pkg),
                        attemptsCount = BlockAttemptStore.getForCurrentMonth(this@ScreenTimeDetailActivity, pkg)
                    )
                    Range.YEAR -> CountSummary(
                        sessionsLabelRes = R.string.usage_sessions_year,
                        attemptsLabelRes = R.string.usage_attempts_year,
                        sessionsCount = OpenCountStore.getForCurrentYearAllProfiles(this@ScreenTimeDetailActivity, pkg),
                        attemptsCount = BlockAttemptStore.getForCurrentYear(this@ScreenTimeDetailActivity, pkg)
                    )
                    Range.OVERALL -> CountSummary(
                        sessionsLabelRes = R.string.usage_sessions_overall,
                        attemptsLabelRes = R.string.usage_attempts_overall,
                        sessionsCount = OpenCountStore.getOverallAllProfiles(this@ScreenTimeDetailActivity, pkg),
                        attemptsCount = BlockAttemptStore.getOverall(this@ScreenTimeDetailActivity, pkg)
                    )
                }
            }

            b.sessions.text = getString(
                R.string.usage_kv_fmt,
                getString(summary.sessionsLabelRes),
                summary.sessionsCount.toString()
            )
            b.attempts.text = getString(
                R.string.usage_kv_fmt,
                getString(summary.attemptsLabelRes),
                summary.attemptsCount.toString()
            )
        }
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
        updateCountSummaryForRange(pkg, range)
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
                    val buckets = withContext(Dispatchers.IO) { UsageStore.getUsageMsMonthBucketsForCurrentYear(this@ScreenTimeDetailActivity, pkg) }
                    if (currentRange != Range.YEAR) return@launch
                    currentSeries = UsageSanity.capSeriesToRange(this@ScreenTimeDetailActivity, buckets.map { it.totalMs }, UsageSanity.RangeCap.YEAR)
                    currentXAxisLabels = buckets.map { monthLabel(it.month1Based, it.year) }
                    b.lineChart.setValues(currentSeries)
                    b.lineChart.setXAxisLabels(currentXAxisLabels)
                    setHeaderUsageTotal(R.string.usage_year_total, UsageSanity.capTotalToRange(this@ScreenTimeDetailActivity, currentSeries.sum(), UsageSanity.RangeCap.YEAR))
                }
            }

            Range.OVERALL -> {
                b.chart.visibility = View.GONE
                b.weekdayRow.visibility = View.GONE
                b.lineChart.visibility = View.VISIBLE
                rangeJob = lifecycleScope.launch {
                    val buckets = withContext(Dispatchers.IO) { UsageStore.getUsageMsMonthBucketsAllTime(this@ScreenTimeDetailActivity, pkg, maxMonths = 36) }
                    if (currentRange != Range.OVERALL) return@launch
                    currentSeries = UsageSanity.capSeriesToRange(this@ScreenTimeDetailActivity, buckets.map { it.totalMs }, UsageSanity.RangeCap.OVERALL)
                    currentXAxisLabels = buckets.map { monthLabel(it.month1Based, it.year, shortYear = true) }
                    b.lineChart.setValues(currentSeries)
                    b.lineChart.setXAxisLabels(currentXAxisLabels)
                    setHeaderUsageTotal(R.string.usage_overall_total, UsageSanity.capTotalToRange(this@ScreenTimeDetailActivity, UsageStore.getUsageMsOverall(this@ScreenTimeDetailActivity, pkg), UsageSanity.RangeCap.OVERALL))
                }
            }
        }
    }

    private data class OverallUsageResult(
        val totalMs: Long,
        val chartMonths: List<Long>
    )

    private fun getMonthTotalAccurate(pkg: String, year: Int, month0: Int): Long {
        val startCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month0)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = (startCal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val now = System.currentTimeMillis()
        val monthStart = startCal.timeInMillis
        val monthEnd = minOf(endCal.timeInMillis, now)
        if (monthEnd <= monthStart) return 0L

        var sum = 0L
        val day = startCal.clone() as Calendar
        while (day.timeInMillis < monthEnd) {
            val dayStart = day.timeInMillis
            val nextDay = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
            val dayEnd = minOf(nextDay, monthEnd)
            sum += UsageStatsRepo.getTotalMsForWindow(this, dayStart, dayEnd, pkg)
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return sum.coerceAtLeast(0L)
    }

    private fun getThisYearMonthsTotalsAccurate(pkg: String): List<Long> {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val curMonth = now.get(Calendar.MONTH)
        val out = ArrayList<Long>(curMonth + 1)
        for (m in Calendar.JANUARY..curMonth) {
            out.add(getMonthTotalAccurate(pkg, year, m))
        }
        return out
    }

    private fun getOverallUsageAccurate(pkg: String, chartMonths: Int): OverallUsageResult {
        val nowMs = System.currentTimeMillis()
        val earliest = UsageStatsRepo.getEarliestAvailableUsageMs(this, 0L, nowMs) ?: nowMs

        val startCal = Calendar.getInstance().apply {
            timeInMillis = earliest
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val allMonths = ArrayList<Long>()
        val cursor = startCal.clone() as Calendar
        while (cursor.timeInMillis <= endCal.timeInMillis) {
            allMonths.add(getMonthTotalAccurate(pkg, cursor.get(Calendar.YEAR), cursor.get(Calendar.MONTH)))
            cursor.add(Calendar.MONTH, 1)
        }

        val total = allMonths.sum()
        val chart = if (allMonths.size > chartMonths) allMonths.takeLast(chartMonths) else allMonths
        return OverallUsageResult(totalMs = total, chartMonths = chart)
    }

    private fun monthLabel(month1Based: Int, year: Int, shortYear: Boolean = false): String {
        val monthName = DateFormatSymbols.getInstance().shortMonths.getOrNull((month1Based - 1).coerceIn(0, 11)).orEmpty().trim().ifBlank { month1Based.toString() }
        return if (shortYear) "$monthName ${year % 100}" else monthName
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
        if (count <= 0) return emptyList()
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

    private fun getLastNMonthsTotals(pkg: String, months: Int): List<Long> {
        val m = months.coerceIn(1, 60)
        val base = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val out = ArrayList<Long>(m)
        for (i in (m - 1) downTo 0) {
            val cal = (base.clone() as Calendar).apply { add(Calendar.MONTH, -i) }
            val year = cal.get(Calendar.YEAR)
            val month0 = cal.get(Calendar.MONTH)
            val fromStore = UsageStore.getUsageMsForMonth(this, pkg, year, month0 + 1)
            val monthTotal = if (fromStore > 0L) {
                fromStore
            } else {
                runCatching {
                    if (i == 0) {
                        UsageStatsRepo.getLastNDaysPerDay(this, pkg, daysSinceStartOfMonth()).sum()
                    } else {
                        val start = cal.timeInMillis
                        val endCal = (cal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
                        val end = endCal.timeInMillis
                        UsageStatsRepo.getTotalMsForWindow(this, start, end, pkg)
                    }
                }.getOrDefault(0L)
            }
            out.add(monthTotal.coerceAtLeast(0L))
        }
        return out
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
            Range.YEAR, Range.OVERALL -> currentXAxisLabels.getOrNull(index) ?: formatMonthLabel(index, series.size)
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
            AppBlockSafety.Level.HARD_EXCLUDED -> {
                Toast.makeText(this, safety.hint ?: getString(R.string.app_picker_protected_generic_hint), Toast.LENGTH_LONG).show()
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

    private fun showAttemptLimitDialog(pkg: String, label: String) {
        ensureAppCanBeManaged(pkg) {
        val profile = ProfileStore.getCurrent(this) ?: return@ensureAppCanBeManaged
        val current = AttemptLimitStore.getLimitAttempts(this, profile, pkg)

        val presets = listOf(0, 3, 5, 10, 15, 20, 30, 50, 100)
        val items = presets.map {
            if (it == 0) getString(R.string.no_limit)
            else resources.getQuantityString(R.plurals.opens_format, it, it)
        } + getString(R.string.custom_value)

        showSwitchlyOptionDialog(
            title = getString(R.string.set_daily_attempt_limit_title, label),
            options = items.mapIndexed { index, item ->
                SwitchlyDialogOption(
                    title = item,
                    iconRes = if (index == items.lastIndex) R.drawable.edit_24 else R.drawable.bar_chart_24,
                    selected = index < presets.size && presets[index] == current
                )
            }
        ) { which ->
            if (which == items.lastIndex) {
                showCustomOpensInput(pkg, label)
                return@showSwitchlyOptionDialog
            }

            val chosen = presets.getOrNull(which) ?: 0
            applyAttemptLimit(profile, pkg, chosen)
        }
        }
    }

    private fun showCustomOpensInput(pkg: String, label: String) {
        val profile = ProfileStore.getCurrent(this) ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.opens_hint)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_daily_attempt_limit_title, label))
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val n = input.text?.toString()?.trim()?.toIntOrNull()
                if (n == null || n < 0 || n > 200) {
                    Toast.makeText(this, R.string.invalid_value, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                applyAttemptLimit(profile, pkg, n)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
    }

    private fun applyAttemptLimit(profile: String, pkg: String, attempts: Int) {
        if (SwitchModeStore.isEnabled(this)) {
            Toast.makeText(this, R.string.toast_disable_switchly_to_edit_app_limits, Toast.LENGTH_SHORT).show()
            return
        }

        val n = attempts.coerceAtLeast(0)
        AttemptLimitStore.setLimitAttempts(this, profile, pkg, n)

        // Reset today's opens if user clears the limit (common expectation).
        if (n == 0) {
            OpenCountStore.setToday(this, profile, pkg, 0)
        }

        BlockingRuntime.ensureRunning(this)
        refreshAttemptLimit(pkg)
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
                    selected = index < presets.size && presets[index] == current
                )
            }
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
        const val RANGE_OVERALL = "overall"
    }
}