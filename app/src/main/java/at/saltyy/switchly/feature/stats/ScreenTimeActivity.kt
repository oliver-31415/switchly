package at.saltyy.switchly.feature.stats

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import at.saltyy.switchly.databinding.ActivityScreenTimeBinding
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.UsageAccess
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import at.saltyy.switchly.ui.dialog.showAccented
import android.widget.RadioButton
import android.widget.RadioGroup
import java.util.concurrent.TimeUnit
import java.util.Calendar

class ScreenTimeActivity : AppCompatActivity() {

    private lateinit var b: ActivityScreenTimeBinding
    private val adapter = ScreenTimeAdapter()

    private enum class Range { TODAY, WEEK, MONTH, YEAR, OVERALL }
    private enum class Filter { ALL_APPS, BLOCKED_ONLY }
    private enum class Sort { USED_TIME, NAME_AZ, NAME_ZA }
    private enum class SortDir { DESC, ASC }

    private var range: Range = Range.TODAY
    private var filter: Filter = Filter.ALL_APPS
    private var sort: Sort = Sort.USED_TIME
    private var sortDir: SortDir = SortDir.DESC

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        b = ActivityScreenTimeBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        // Toolbar menu as a fallback (always visible)
        b.toolbar.inflateMenu(at.saltyy.switchly.R.menu.menu_stats)
        b.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == at.saltyy.switchly.R.id.action_sort_filter) {
                showSortFilterDialog()
                true
            } else false
        }

        b.rvApps.layoutManager = LinearLayoutManager(this)
        b.rvApps.adapter = adapter

        b.btnOpenUsageAccess.setOnClickListener {
            startActivity(UsageAccess.settingsIntent())
        }

        // Range chips (same look as the other stats screens)
        b.chipToday.isChecked = true
        b.chipToday.setOnClickListener { range = Range.TODAY; refresh() }
        b.chipWeek.setOnClickListener { range = Range.WEEK; refresh() }
        b.chipMonth.setOnClickListener { range = Range.MONTH; refresh() }
        b.chipYear.setOnClickListener { range = Range.YEAR; refresh() }
        b.chipOverall.setOnClickListener { range = Range.OVERALL; refresh() }

        b.btnSortFilter.setOnClickListener { showSortFilterDialog() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ok = UsageAccess.hasUsageAccess(this)
        b.groupMissing.isVisible = !ok
        b.groupContent.isVisible = ok

        if (!ok) return

        Thread {
            val rows = loadForRange(range)
            val shown = applyFilterAndSort(rows)

            val totalMs = shown.sumOf { it.usedMs.coerceAtLeast(0L) }
            val totalPretty = formatMsPretty(totalMs)

            runOnUiThread {
                b.tvTotal.text = getString(at.saltyy.switchly.R.string.screen_time_total_fmt, totalPretty)
                adapter.submit(shown)
            }
        }.start()
    }

    private fun loadForRange(r: Range): List<ScreenTimeRow> {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()

        val from = when (r) {
            Range.TODAY -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            Range.WEEK -> now - TimeUnit.DAYS.toMillis(7)
            Range.MONTH -> now - TimeUnit.DAYS.toMillis(30)
            Range.YEAR -> now - TimeUnit.DAYS.toMillis(365)
            Range.OVERALL -> now - TimeUnit.DAYS.toMillis(365) // keep it performant
        }

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, now).orEmpty()
            .filter { it.totalTimeInForeground > 0L }
            .filter { it.packageName != packageName }

        val pm = packageManager
        val launchable = stats.filter { pm.getLaunchIntentForPackage(it.packageName) != null }
        return launchable
            .map { s ->
                val label = runCatching {
                    val ai = pm.getApplicationInfo(s.packageName, 0)
                    pm.getApplicationLabel(ai).toString()
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: s.packageName

                ScreenTimeRow(
                    packageName = s.packageName,
                    appName = label,
                    usedMs = s.totalTimeInForeground
                )
            }
    }

    private fun applyFilterAndSort(rows: List<ScreenTimeRow>): List<ScreenTimeRow> {
        val profile = ProfileStore.getCurrent(this)
        val blocked = if (profile != null) ProfileStore.getBlockedForProfile(this, profile).toSet() else emptySet()

        val filtered = when (filter) {
            Filter.ALL_APPS -> rows
            Filter.BLOCKED_ONLY -> rows.filter { it.packageName in blocked }
        }

        return when (sort) {
            Sort.NAME_AZ -> filtered.sortedBy { it.appName.lowercase() }
            Sort.NAME_ZA -> filtered.sortedByDescending { it.appName.lowercase() }
            Sort.USED_TIME -> {
                val base = filtered.sortedBy { it.usedMs }
                if (sortDir == SortDir.ASC) base else base.reversed()
            }
        }
    }

    private fun showSortFilterDialog() {
        val v = layoutInflater.inflate(at.saltyy.switchly.R.layout.dialog_sort_filter, null)
        val rgFilter = v.findViewById<RadioGroup>(at.saltyy.switchly.R.id.rgFilter)
        val rgSort = v.findViewById<RadioGroup>(at.saltyy.switchly.R.id.rgSort)

        // Labels for screen time are always "used time"
        v.findViewById<RadioButton>(at.saltyy.switchly.R.id.rbSortPrimaryDesc).text = getString(at.saltyy.switchly.R.string.stats_sort_used_time_desc)
        v.findViewById<RadioButton>(at.saltyy.switchly.R.id.rbSortPrimaryAsc).text = getString(at.saltyy.switchly.R.string.stats_sort_used_time_asc)

        rgFilter.check(if (filter == Filter.BLOCKED_ONLY) at.saltyy.switchly.R.id.rbFilterBlocked else at.saltyy.switchly.R.id.rbFilterAll)
        rgSort.check(
            when (sort) {
                Sort.NAME_AZ -> at.saltyy.switchly.R.id.rbSortAz
                Sort.NAME_ZA -> at.saltyy.switchly.R.id.rbSortZa
                Sort.USED_TIME -> if (sortDir == SortDir.ASC) at.saltyy.switchly.R.id.rbSortPrimaryAsc else at.saltyy.switchly.R.id.rbSortPrimaryDesc
            }
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(at.saltyy.switchly.R.string.stats_sort_filter_title))
            .setView(v)
            .setPositiveButton(getString(at.saltyy.switchly.R.string.stats_apply)) { _, _ ->
                filter = if (rgFilter.checkedRadioButtonId == at.saltyy.switchly.R.id.rbFilterBlocked) Filter.BLOCKED_ONLY else Filter.ALL_APPS
                when (rgSort.checkedRadioButtonId) {
                    at.saltyy.switchly.R.id.rbSortAz -> sort = Sort.NAME_AZ
                    at.saltyy.switchly.R.id.rbSortZa -> sort = Sort.NAME_ZA
                    at.saltyy.switchly.R.id.rbSortPrimaryAsc -> { sort = Sort.USED_TIME; sortDir = SortDir.ASC }
                    else -> { sort = Sort.USED_TIME; sortDir = SortDir.DESC }
                }
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
    }

    private fun formatMsPretty(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalSec = (ms/1000L).toInt()
        val h = totalSec/3600
        val m = (totalSec % 3600)/60
        val s = totalSec % 60
        return when {
            h == 0 && m == 0 -> "${s}s"
            h == 0 -> if (s > 0) "${m}m ${s}s" else "${m}m"
            else -> "%dh %02dm".format(h, m)
        }
    }
}
