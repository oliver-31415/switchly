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
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.DomainLimitStore
import at.saltyy.switchly.data.prefs.ProfileRuleModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.WebUsageStore
import at.saltyy.switchly.databinding.ActivityWebsiteDetailBinding
import at.saltyy.switchly.feature.settings.ManageBlockedWebsitesActivity
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showSwitchlyOptionDialog
import at.saltyy.switchly.ui.dialog.showSwitchlyMultiChoiceDialog
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.EditingLockGuard
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

class WebsiteDetailActivity : AppCompatActivity() {

    private fun toolbarIconColor(): Int {
        return if (MaterialColors.isColorLight(AccentColor.getToolbarColor(this))) Color.BLACK else Color.WHITE
    }

    private fun syncWebsiteEditingUi() {
        val locked = EditingLockGuard.isLocked(this)
        b.btnEditLimits.isEnabled = !locked
        b.btnManageBlocking.isEnabled = !locked
        b.btnEditLimits.alpha = if (locked) 0.62f else 1f
        b.btnManageBlocking.alpha = if (locked) 0.62f else 1f
        b.toolbar.menu?.findItem(R.id.action_delete)?.apply {
            isEnabled = !locked
            icon?.mutate()?.alpha = if (locked) 120 else 255
        }
    }

    private fun websiteEditingLocked(): Boolean {
        val locked = EditingLockGuard.isLocked(this)
        if (locked) {
            EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_websites)
        }
        return locked
    }

    private lateinit var b: ActivityWebsiteDetailBinding

    private var currentRange: Range = Range.TODAY
    private var currentSeries: List<Long> = emptyList()

    private enum class Range { TODAY, WEEK, MONTH, YEAR, OVERALL }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        b = ActivityWebsiteDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        b.toolbar.navigationIcon?.mutate()?.setTint(toolbarIconColor())
        b.toolbar.setNavigationOnClickListener { finish() }
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = b.toolbar)

        b.toolbar.inflateMenu(R.menu.menu_website_detail)
        b.toolbar.menu?.findItem(R.id.action_delete)?.icon?.mutate()?.setTint(toolbarIconColor())

        val domain = intent.getStringExtra(EXTRA_DOMAIN) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: domain
        b.toolbar.title = label

        b.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_delete) {
                if (websiteEditingLocked()) return@setOnMenuItemClickListener true
                showDeleteDialog(domain, label)
                true
            } else false
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SwitchModeStore.enabledFlow.collect {
                    runOnUiThread { syncWebsiteEditingUi() }
                }
            }
        }

        // Ensure pending increments from the Accessibility service are included.
        WebUsageStore.flush(this)

        // Today
        val today = WebUsageStore.getUsageMsToday(this, domain)
        b.todayUsage.text = getString(
            R.string.usage_kv_fmt,
            getString(R.string.usage_today),
            StatsFormat.prettyMsWithSeconds(today)
        )

        refreshDailyLimit(domain)
        b.btnEditLimits.setOnClickListener {
            if (websiteEditingLocked()) return@setOnClickListener
            QuickLimitDialogs.showForWebsite(
                activity = this,
                domain = domain,
                label = label
            ) {
                refreshDailyLimit(domain)
            }
        }

        b.btnManageBlocking.setOnClickListener {
            if (websiteEditingLocked()) return@setOnClickListener
            if (EditingLockGuard.isLocked(this)) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_websites)
            } else {
                startActivity(Intent(this, ManageBlockedWebsitesActivity::class.java))
            }
        }

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

        b.toggleRange.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val range = when (checkedId) {
                b.btnRangeToday.id -> Range.TODAY
                b.btnRangeMonth.id -> Range.MONTH
                b.btnRangeYear.id -> Range.YEAR
                b.btnRangeOverall.id -> Range.OVERALL
                else -> Range.WEEK
            }
            applyRange(domain, range)
        }

        applyRange(domain, initialRange)
    }

    override fun onResume() {
        super.onResume()
        val domain = intent.getStringExtra(EXTRA_DOMAIN) ?: return
        WebUsageStore.flush(this)
        refreshDailyLimit(domain)
        applyRange(domain, currentRange())
        syncWebsiteEditingUi()
    }

    private fun currentRange(): Range {
        return when (b.toggleRange.checkedButtonId) {
            b.btnRangeToday.id -> Range.TODAY
            b.btnRangeMonth.id -> Range.MONTH
            b.btnRangeYear.id -> Range.YEAR
            b.btnRangeOverall.id -> Range.OVERALL
            else -> Range.WEEK
        }
    }

    private fun applyRange(domain: String, range: Range) {
        currentRange = range
        when (range) {
            Range.TODAY -> {
                WebUsageStore.flush(this)
                currentSeries = listOf(WebUsageStore.getUsageMsToday(this, domain))
                b.chart.visibility = View.GONE
                b.weekdayRow.visibility = View.GONE
                b.lineChart.visibility = View.GONE
                val total = currentSeries.sum()
                b.rangeTotal.text = getString(
                    R.string.usage_kv_fmt,
                    getString(R.string.usage_today),
                    StatsFormat.prettyMsWithSeconds(total)
                )
            }

            Range.WEEK -> {
                val perDay = WebUsageStore.getUsageMsForLastNDays(this, domain, 7)
                currentSeries = perDay
                b.chart.visibility = View.VISIBLE
                b.weekdayRow.visibility = View.VISIBLE
                b.lineChart.visibility = View.GONE
                b.chart.setValues(perDay)
                b.lineChart.setXAxisLabels(emptyList())

                val total = perDay.sum()
                b.rangeTotal.text = getString(
                    R.string.usage_kv_fmt,
                    getString(R.string.usage_week_total),
                    StatsFormat.prettyMsWithSeconds(total)
                )
            }

            Range.MONTH -> {
                val days = daysSinceStartOfMonth()
                val perDay = WebUsageStore.getUsageMsForLastNDays(this, domain, days)
                currentSeries = perDay
                b.chart.visibility = View.GONE
                b.weekdayRow.visibility = View.GONE
                b.lineChart.visibility = View.VISIBLE
                b.lineChart.setValues(perDay)
                b.lineChart.setXAxisLabels(buildDayIndexLabels(perDay.size))

                val total = perDay.sum()
                b.rangeTotal.text = getString(
                    R.string.usage_kv_fmt,
                    getString(R.string.usage_month_total),
                    StatsFormat.prettyMsWithSeconds(total)
                )
            }

            Range.YEAR -> {
                val perMonth = getThisYearMonthsTotals(domain)
                currentSeries = perMonth
                b.chart.visibility = View.GONE
                b.weekdayRow.visibility = View.GONE
                b.lineChart.visibility = View.VISIBLE
                b.lineChart.setValues(perMonth)
                b.lineChart.setXAxisLabels(buildThisYearMonthLabels(perMonth.size))

                val total = perMonth.sum()
                b.rangeTotal.text = getString(
                    R.string.usage_kv_fmt,
                    getString(R.string.usage_year_total),
                    StatsFormat.prettyMsWithSeconds(total)
                )
            }

            Range.OVERALL -> {
                val perMonth = getOverallMonthsTotals(domain, maxMonths = 36)
                currentSeries = perMonth
                b.chart.visibility = View.GONE
                b.weekdayRow.visibility = View.GONE
                b.lineChart.visibility = View.VISIBLE
                b.lineChart.setValues(perMonth)
                b.lineChart.setXAxisLabels(buildLastNMonthLabels(perMonth.size))

                val totalRaw = WebUsageStore.getUsageMsAllTime(this, domain)
                val seriesSum = perMonth.sum()
                val total = if (totalRaw == 0L && seriesSum > 0L) seriesSum else totalRaw
                b.rangeTotal.text = getString(
                    R.string.usage_kv_fmt,
                    getString(R.string.usage_overall_total),
                    StatsFormat.prettyMsWithSeconds(total)
                )
            }
        }
    }

    private fun buildDayIndexLabels(count: Int): List<String> {
        return (1..count.coerceAtLeast(0)).map { it.toString() }
    }

    private fun buildThisYearMonthLabels(count: Int): List<String> {
        val dfs = DateFormatSymbols.getInstance()
        val months = dfs.shortMonths
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
        return c.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1).coerceAtMost(31)
    }

    private fun getThisYearMonthsTotals(domain: String): List<Long> {
        // Sum daily buckets from Jan 1st -> today into month buckets.
        val now = Calendar.getInstance()
        val start = Calendar.getInstance().apply {
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val dayMs = 24L * 60L * 60L * 1000L
        val days = (((now.timeInMillis/dayMs) - (start.timeInMillis/dayMs)) + 1).toInt().coerceAtLeast(1).coerceAtMost(366)
        val daily = WebUsageStore.getUsageMsForLastNDays(this, domain, days)

        val cal = start.clone() as Calendar
        val sumsByMonth = linkedMapOf<Int, Long>()
        for (v in daily) {
            val key = cal.get(Calendar.YEAR) * 100 + (cal.get(Calendar.MONTH) + 1)
            sumsByMonth[key] = (sumsByMonth[key] ?: 0L) + v
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Build Jan -> current month contiguous list
        val curMonth = Calendar.getInstance().get(Calendar.MONTH)
        val out = ArrayList<Long>(12)
        val y = Calendar.getInstance().get(Calendar.YEAR)
        for (m in Calendar.JANUARY..curMonth) {
            val key = y * 100 + (m + 1)
            out.add(sumsByMonth[key] ?: 0L)
        }
        return out
    }

    private fun getOverallMonthsTotals(domain: String, maxMonths: Int): List<Long> {
        val sumsByMonth = WebUsageStore.getUsageMsPerMonthAllTime(this, domain)

        // Always return a contiguous series ending in the current month.
        // This makes the chart predictable and prevents "empty" charts on devices with sparse history.
        val m = maxMonths.coerceIn(1, 60)
        val cur = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val iter = (cur.clone() as Calendar).apply { add(Calendar.MONTH, -(m - 1)) }
        val out = ArrayList<Long>(m)
        for (i in 0 until m) {
            val k = iter.get(Calendar.YEAR) * 100 + (iter.get(Calendar.MONTH) + 1)
            out.add(sumsByMonth[k] ?: 0L)
            iter.add(Calendar.MONTH, 1)
        }
        return out
    }
    private fun getLast12MonthsTotals(domain: String): List<Long> {
        val daily = WebUsageStore.getUsageMsForLastNDays(this, domain, 366)
        if (daily.isEmpty()) return emptyList()

        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(daily.size - 1)) }

        val sumsByMonth = linkedMapOf<Int, Long>() // YYYYMM -> sum
        for (v in daily) {
            val key = cal.get(Calendar.YEAR) * 100 + (cal.get(Calendar.MONTH) + 1)
            sumsByMonth[key] = (sumsByMonth[key] ?: 0L) + v
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val keys = sumsByMonth.keys.toList()
        val lastKeys = if (keys.size > 12) keys.takeLast(12) else keys
        return lastKeys.map { sumsByMonth[it] ?: 0L }
    }

    private fun setupChartInteractions(label: String) {
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
            Range.YEAR, Range.OVERALL -> formatMonthLabel(index, series.size)
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

    private fun refreshDailyLimit(domain: String) {
        val normalized = DomainBlockStore.normalize(domain) ?: domain
        val isAlways = DomainBlockStore.getDomains(this).contains(normalized)
        val allowMode = ProfileStore.getCurrent(this)?.let { ProfileRuleModeStore.isAllowMode(this, it) } == true
        val limitMin = DomainLimitStore.getLimitMinutes(this, normalized)

        b.tvDailyLimitValue.text = when {
            isAlways -> getString(if (allowMode) R.string.rule_allowed_always else R.string.rule_block_always)
            limitMin > 0 -> getString(R.string.daily_limit_value_format, limitMin)
            else -> getString(R.string.no_limit)
        }
    }

    private fun showDailyLimitDialog(domain: String, label: String) {
        if (websiteEditingLocked()) return
        val current = DomainLimitStore.getLimitMinutes(this, domain)
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
                showCustomMinutesInput(domain, label)
                return@showSwitchlyOptionDialog
            }

            val chosen = presets.getOrNull(which) ?: 0
            applyDailyLimit(domain, chosen)
        }
    }

    private fun showCustomMinutesInput(domain: String, label: String) {
        if (websiteEditingLocked()) return
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
                applyDailyLimit(domain, m)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
    }

    private fun applyDailyLimit(domain: String, minutes: Int) {
        if (websiteEditingLocked()) return
        val m = minutes.coerceAtLeast(0)
        DomainLimitStore.setLimitMinutes(this, domain, m)

        // Reset today's usage if user clears the limit (common expectation).
        if (m == 0) {
            WebUsageStore.setUsageMsToday(this, domain, 0L)
        }

        BlockingRuntime.ensureRunning(this)
        refreshDailyLimit(domain)
    }

    private fun showDeleteDialog(domain: String, label: String) {
        if (websiteEditingLocked()) return
        val clearUsage = booleanArrayOf(true)
        val removeLimit = booleanArrayOf(false)
        val removeBlock = booleanArrayOf(false)

        val items = arrayOf(
            getString(R.string.website_delete_clear_usage),
            getString(R.string.website_delete_remove_limit),
            getString(R.string.website_delete_remove_block_rule)
        )
        val checked = booleanArrayOf(true, false, false)

        showSwitchlyMultiChoiceDialog(
            title = getString(R.string.website_delete_title, label),
            options = items.map { SwitchlyDialogOption(title = it, destructive = true) },
            checked = checked,
            positiveTextRes = R.string.delete
        ) { result ->
            clearUsage[0] = result.getOrNull(0) == true
            removeLimit[0] = result.getOrNull(1) == true
            removeBlock[0] = result.getOrNull(2) == true

            if (clearUsage[0]) WebUsageStore.clearAllUsage(this, domain)
            if (removeLimit[0]) DomainLimitStore.clear(this, domain)
            if (removeBlock[0]) DomainBlockStore.removeDomain(this, domain)

            Toast.makeText(this, R.string.website_deleted_toast, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setWeekdayLabels() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(6)
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
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_LABEL = "label"
        const val EXTRA_INITIAL_RANGE = "initial_range"
        const val RANGE_TODAY = "today"
        const val RANGE_WEEK = "week"
        const val RANGE_MONTH = "month"
        const val RANGE_YEAR = "year"
        const val RANGE_OVERALL = "overall"
    }
}
