package at.saltyy.switchly.feature.usage
import android.os.Bundle
import android.view.View
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.concurrent.TimeUnit
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import at.saltyy.switchly.R
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.LimitReachedStore
import at.saltyy.switchly.data.prefs.AttemptLimitStore
import at.saltyy.switchly.data.prefs.OpenCountStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.databinding.ActivityScreenTimeDetailBinding
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.ui.dialog.showAccented
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.launch

class ScreenTimeDetailActivity : AppCompatActivity() {

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

    private var currentRange: Range = Range.WEEK
    private var currentSeries: List<Long> = emptyList()

    private enum class Range { WEEK, MONTH, YEAR, OVERALL }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        b = ActivityScreenTimeDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }

        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: pkg

        b.toolbar.title = label

        val summaryToday = UsageStatsRepo.getTodaySummary(this, topN = 200)
        val today = summaryToday.topApps.firstOrNull { it.packageName == pkg }?.timeMs ?: 0L
        val sessions = UsageStatsRepo.getSessionsToday(this, pkg)

        // icon/name
        val icon = summaryToday.topApps.firstOrNull { it.packageName == pkg }?.icon
        b.icon.setImageDrawable(icon)

        b.todayUsage.text = getString(
            R.string.usage_kv_fmt,
            getString(R.string.usage_today),
            StatsFormat.prettyMsWithSeconds(today)
        )
        b.sessions.text = getString(
            R.string.usage_kv_fmt,
            getString(R.string.usage_sessions_today),
            sessions.toString()
        )

        // Limits (edited via the single "tune" icon)
        refreshDailyLimit(pkg)
        refreshAttemptLimit(pkg)
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
            RANGE_MONTH -> Range.MONTH
            RANGE_YEAR -> Range.YEAR
            RANGE_OVERALL -> Range.OVERALL
            else -> Range.WEEK
        }
        setWeekdayLabels()
        b.toggleRange.check(when (initialRange) {
            Range.MONTH -> b.btnRangeMonth.id
            Range.YEAR -> b.btnRangeYear.id
            Range.OVERALL -> b.btnRangeOverall.id
            else -> b.btnRangeWeek.id
        })
        setupChartInteractions(label)

        b.toggleRange.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val range = when (checkedId) {
                b.btnRangeMonth.id -> Range.MONTH
                b.btnRangeYear.id -> Range.YEAR
                b.btnRangeOverall.id -> Range.OVERALL
                else -> Range.WEEK
            }
            applyRange(pkg, range)
        }

        applyRange(pkg, initialRange)
    }

    override fun onResume() {
        super.onResume()
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        b.tvProfileIndicator.text = ProfileStore.getCurrent(this)?.let {
            getString(R.string.profile_active_fmt, it)
        }.orEmpty()
        refreshDailyLimit(pkg)
        refreshAttemptLimit(pkg)
        syncLimitEditingUi()
    }

    private fun applyRange(pkg: String, range: Range) {
        currentRange = range
        when (range) {
            Range.WEEK -> {
                val perDay = UsageStatsRepo.getLast7DaysPerDay(this, pkg)
                currentSeries = perDay
                b.chart.visibility = View.VISIBLE
                b.weekdayRow.visibility = View.VISIBLE
                b.lineChart.visibility = View.GONE
                b.chart.setValues(perDay)
                b.lineChart.setXAxisLabels(emptyList())

                val total = perDay.sum()
                b.weekTotal.text = getString(
                    R.string.usage_kv_fmt,
                    getString(R.string.usage_week_total),
                    StatsFormat.prettyMsWithSeconds(total)
                )
            }

            Range.MONTH -> {
                val days = daysSinceStartOfMonth()
                val perDay = UsageStatsRepo.getLastNDaysPerDay(this, pkg, days)
                currentSeries = perDay
                b.chart.visibility = View.GONE
                b.weekdayRow.visibility = View.GONE
                b.lineChart.visibility = View.VISIBLE
                b.lineChart.setValues(perDay)
                b.lineChart.setXAxisLabels(buildDayIndexLabels(perDay.size))

                val total = perDay.sum()
                b.weekTotal.text = getString(
                    R.string.usage_kv_fmt,
                    getString(R.string.usage_month_total),
                    StatsFormat.prettyMsWithSeconds(total)
                )
            }

            Range.YEAR -> {
                val perMonth = getThisYearMonthsTotals(pkg)
                currentSeries = perMonth
                b.chart.visibility = View.GONE
                b.weekdayRow.visibility = View.GONE
                b.lineChart.visibility = View.VISIBLE
                b.lineChart.setValues(perMonth)
                b.lineChart.setXAxisLabels(buildThisYearMonthLabels(perMonth.size))

                val total = perMonth.sum()
                b.weekTotal.text = getString(
                    R.string.usage_kv_fmt,
                    getString(R.string.usage_year_total),
                    StatsFormat.prettyMsWithSeconds(total)
                )
            }

            Range.OVERALL -> {
                val perMonth = getLastNMonthsTotals(pkg, months = 36)
                currentSeries = perMonth
                b.chart.visibility = View.GONE
                b.weekdayRow.visibility = View.GONE
                b.lineChart.visibility = View.VISIBLE
                b.lineChart.setValues(perMonth)
                b.lineChart.setXAxisLabels(buildLastNMonthLabels(perMonth.size))

                val now = System.currentTimeMillis()
                // Some OEMs behave oddly when querying from epoch (0L). If that returns 0 but the
                // chart clearly has data, fall back to the visible series sum.
                val totalRaw = UsageStatsRepo.getTotalMsForWindow(this, 0L, now, pkg)
                val seriesSum = perMonth.sum()
                val total = if (totalRaw == 0L && seriesSum > 0L) seriesSum else totalRaw
                b.weekTotal.text = getString(
                    R.string.usage_kv_fmt,
                    getString(R.string.usage_overall_total),
                    StatsFormat.prettyMsWithSeconds(total)
                )
            }
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
        val cal = Calendar.getInstance().apply {
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val out = ArrayList<Long>(12)
        val cur = Calendar.getInstance()
        val curYear = cur.get(Calendar.YEAR)
        val curMonth = cur.get(Calendar.MONTH)

        for (m in Calendar.JANUARY..curMonth) {
            val startCal = (cal.clone() as Calendar).apply { set(Calendar.MONTH, m) }
            val endCal = (startCal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            val start = startCal.timeInMillis
            val end = if (m == curMonth) now else endCal.timeInMillis
            out.add(UsageStatsRepo.getTotalMsForWindow(this, start, end, pkg))
        }
        return out
    }

    private fun getLastNMonthsTotals(pkg: String, months: Int): List<Long> {
        // Query month-by-month windows. This is slower than trying to bucket raw stats, but it is:
        // - accurate
        // - stable across OEM implementations
        // - avoids weird results when querying from epoch (0L)
        val m = months.coerceIn(1, 60)
        val now = System.currentTimeMillis()

        val base = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val out = ArrayList<Long>(m)
        for (i in (m - 1) downTo 0) {
            val startCal = (base.clone() as Calendar).apply { add(Calendar.MONTH, -i) }
            val endCal = (startCal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            val start = startCal.timeInMillis
            val end = if (i == 0) now else endCal.timeInMillis
            out.add(UsageStatsRepo.getTotalMsForWindow(this, start, end, pkg))
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
            else getString(R.string.daily_limit_value_format, limitMin)
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

    private fun showAttemptLimitDialog(pkg: String, label: String) {
        val profile = ProfileStore.getCurrent(this) ?: return
        val current = AttemptLimitStore.getLimitAttempts(this, profile, pkg)

        val presets = listOf(0, 3, 5, 10, 15, 20, 30, 50, 100)
        val items = presets.map {
            if (it == 0) getString(R.string.no_limit)
            else resources.getQuantityString(R.plurals.opens_format, it, it)
        } + getString(R.string.custom_value)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_daily_attempt_limit_title, label))
            .setSingleChoiceItems(items.toTypedArray(), presets.indexOf(current).takeIf { it >= 0 } ?: -1) { dialog, which ->
                if (which == items.lastIndex) {
                    dialog.dismiss()
                    showCustomOpensInput(pkg, label)
                    return@setSingleChoiceItems
                }

                val chosen = presets.getOrNull(which) ?: 0
                applyAttemptLimit(profile, pkg, chosen)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
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
        val profile = ProfileStore.getCurrent(this) ?: return

        val current = UsageLimitStore.getLimitMinutes(this, profile, pkg)
        val presets = listOf(0, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120)
        val items = presets.map {
            if (it == 0) getString(R.string.no_limit) else resources.getQuantityString(R.plurals.minutes_format, it, it)
        } + getString(R.string.custom_minutes)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_daily_limit_title, label))
            .setSingleChoiceItems(items.toTypedArray(), presets.indexOf(current).takeIf { it >= 0 } ?: -1) { dialog, which ->
                if (which == items.lastIndex) {
                    dialog.dismiss()
                    showCustomMinutesInput(pkg, label)
                    return@setSingleChoiceItems
                }

                val chosen = presets.getOrNull(which) ?: 0
                applyDailyLimit(profile, pkg, chosen)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
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
            val blocked = ProfileStore.getBlockedForProfile(this, profile).toMutableSet()
            if (!blocked.contains(pkg)) {
                blocked.add(pkg)
                ProfileStore.setBlockedForProfile(this, profile, blocked)
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
        const val RANGE_WEEK = "week"
        const val RANGE_MONTH = "month"
        const val RANGE_YEAR = "year"
        const val RANGE_OVERALL = "overall"
    }
}