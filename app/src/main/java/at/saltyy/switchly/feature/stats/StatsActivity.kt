package at.saltyy.switchly.feature.stats

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.util.LruCache
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.BlockAttemptStore
import at.saltyy.switchly.data.prefs.BlockCountStore
import at.saltyy.switchly.data.prefs.BlockedTimeStore
import at.saltyy.switchly.data.prefs.BlockedInboxStore
import at.saltyy.switchly.data.prefs.NfcScanCountStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ScheduleExecutionCountStore
import at.saltyy.switchly.data.prefs.SwitchlyRuntimeStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.databinding.ActivityStatsBinding
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.util.AppUsageToday
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import at.saltyy.switchly.feature.inbox.BlockedInboxActivity
import at.saltyy.switchly.data.prefs.BlockedNotificationEvent
import at.saltyy.switchly.ui.dialog.showAccented

class StatsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "stats_mode"

        fun intent(ctx: Context, mode: String): Intent {
            return Intent(ctx, StatsActivity::class.java).putExtra(EXTRA_MODE, mode)
        }
    }

    private lateinit var b: ActivityStatsBinding
    private lateinit var usageAdapter: StatsAdapter
    private lateinit var blockAdapter: BlockStatsAdapter
    private lateinit var runtimeAdapter: RuntimeBlockedAdapter

    private enum class Mode { USAGE, RUNTIME, BLOCKING, OTHER }
    private var mode: Mode = Mode.USAGE

    // Premium gating: Only TODAY is available without premium
    private enum class Range { TODAY, WEEK, MONTH, YEAR, OVERALL }

    // Sorting: A-Z and Used Time
    private enum class Sort { NAME_AZ, NAME_ZA, USED_TIME, ATTEMPTS, BLOCKED_TIME }
    private enum class SortDir { DESC, ASC }
    private enum class Filter { ALL_APPS, BLOCKED_ONLY }

    private var range: Range = Range.TODAY
    private var sort: Sort = Sort.USED_TIME
    private var sortDir: SortDir = SortDir.DESC
    private var filter: Filter = Filter.ALL_APPS
    private var lastRows: List<StatsRow> = emptyList()
    private var lastBlockRows: List<BlockStatsRow> = emptyList()
    private var lastRuntimeRows: List<RuntimeBlockedRow> = emptyList()
    private var currentBlockedSet: Set<String> = emptySet()
    private var loadJob: Job? = null
    private val labelCache = LruCache<String, String>(200)

    private data class Improvement(
        val pkg: String,
        val label: String,
        val percentDrop: Int,
        val msDrop: Long
    )

    private data class InsightsUi(
        val trend: String,
        val ratio: String,
        val improved: String
    )

    private data class OverallAgg(
        val usageMs: Map<String, Long>,
        val blockedMs: Map<String, Long>,
        val blockedCount: Map<String, Int>,
        val attemptCount: Map<String, Int>
    )

    private sealed interface ComputedBase {
        val pkgs: List<String>
        val weekDays: Int
        val yearNow: Int
        val monthNow1: Int
    }

    private data class UsageComputed(
        override val pkgs: List<String>,
        override val weekDays: Int,
        override val yearNow: Int,
        override val monthNow1: Int,
        val rows: List<StatsRow>,
        val insights: InsightsUi?
    ) : ComputedBase

    private data class BlockingComputed(
        override val pkgs: List<String>,
        override val weekDays: Int,
        override val yearNow: Int,
        override val monthNow1: Int,
        val totalBlockedMs: Long,
        val totalBlocks: Int,
        val totalAttempts: Int,
        val blockedMessages: Int,
        val rows: List<BlockStatsRow>
    ) : ComputedBase

    private data class RuntimeComputed(
        override val pkgs: List<String>,
        override val weekDays: Int,
        override val yearNow: Int,
        override val monthNow1: Int,
        val runtimeMs: Long,
        val totalBlockedMs: Long,
        val rows: List<RuntimeBlockedRow>
    ) : ComputedBase

    private data class OtherComputed(
        override val pkgs: List<String>,
        override val weekDays: Int,
        override val yearNow: Int,
        override val monthNow1: Int,
        val emergencyUsed: Int,
        val emergencyTotal: Int,
        val nfcUsed: Int,
        val nfcTotal: Int,
        val schedulesExecuted: Int,
        val schedulesTotal: Int,
        val qrScans: Int,
        val qrTotal: Int,
        val tempEnables: Int,
        val tempTotal: Int,
        val limitsReached: Int,
        val limitsTotal: Int,
        val profilesCount: Int,
        val profilesTotal: Int,
        val enabledSchedules: Int,
        val enabledSchedulesTotal: Int,
        val limitedApps: Int,
        val limitedAppsTotal: Int,
        val blockedAppsNow: Int,
        val blockedAppsTotal: Int,
        val blockedAttempts: Int,
        val blockedAttemptsTotal: Int
    ) : ComputedBase

    /**
     * "Week" in the UI means *current calendar week to date* (Mon..today), not "last 7 days".
     * Calendar.DAY_OF_WEEK: 1=Sunday, 2=Monday, ... 7=Saturday
     */
    private fun isInstalled(pkg: String): Boolean {
        return try {
            packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun weekToDateDays(calNow: Calendar = Calendar.getInstance()): Int {
        val dow = calNow.get(Calendar.DAY_OF_WEEK)
        // Convert to Monday=0..Sunday=6
        val offsetFromMonday = (dow + 5) % 7
        return (offsetFromMonday + 1).coerceIn(1, 7)
    }

    private fun rangeStartMs(range: Range, weekDays: Int, yearNow: Int, monthNow1: Int): Long? {
        val cal = Calendar.getInstance()
        // Start from today 00:00
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        return when (range) {
            Range.TODAY -> cal.timeInMillis
            Range.WEEK -> {
                // "Week" = calendar week to date (Mon..today) => we stored weekDays accordingly.
                val daysBack = (weekDays - 1).coerceAtLeast(0)
                cal.add(Calendar.DAY_OF_YEAR, -daysBack)
                cal.timeInMillis
            }
            Range.MONTH -> {
                cal.set(Calendar.YEAR, yearNow)
                cal.set(Calendar.MONTH, (monthNow1 - 1).coerceIn(0, 11))
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.timeInMillis
            }
            Range.YEAR -> {
                cal.set(Calendar.YEAR, yearNow)
                cal.set(Calendar.MONTH, Calendar.JANUARY)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.timeInMillis
            }
            Range.OVERALL -> null
        }
    }

    private fun blockedInboxCountForRange(
        profile: String,
        range: Range,
        weekDays: Int,
        yearNow: Int,
        monthNow1: Int
    ): Int {
        val start = rangeStartMs(range, weekDays, yearNow, monthNow1)
        val events = BlockedInboxStore.getAll(this)
        return events.asSequence()
            .filter { it.profile == profile }
            .filter { start == null || it.timeMillis >= start }
            .count()
    }

    // Blocking screen should show inbox totals (all time), not just the selected range.
    private fun blockedInboxCountOverall(profile: String): Int {
        val events = BlockedInboxStore.getAll(this)
        return events.asSequence().count { it.profile == profile }
    }

    private fun showBlockingDetails(row: BlockStatsRow) {
        val profile = ProfileStore.getCurrent(this) ?: return

        val calNow = Calendar.getInstance()
        val weekDays = weekToDateDays(calNow)
        val yearNow = calNow.get(Calendar.YEAR)
        val monthNow1 = calNow.get(Calendar.MONTH) + 1

        val events = BlockedInboxStore.getAll(this)
            .asSequence()
            .filter { it.profile == profile }
            .filter { it.pkg == row.packageName }
            .sortedByDescending { it.timeMillis }
            .toList()

        val blockedMsgCount = events.size
        val last3 = events.take(3)

        fun shorten(s: String, max: Int): String {
            val t = s.trim()
            if (t.isEmpty()) return ""
            return if (t.length <= max) t else t.take(max - 1) + "…"
        }

        val msg = buildString {
            // Keep it focused: we show blocked messages, not internal attempt counters.
            append(getString(R.string.stats_blocked_messages_line, blockedMsgCount))

            if (last3.isNotEmpty()) {
                append("\n\n")
                append(getString(R.string.stats_last_blocked_messages_title))
                for (e in last3) {
                    val title = shorten(e.title.ifBlank { e.pkg }, 28)
                    val body = shorten(
                        when {
                            e.bigText.isNotBlank() -> e.bigText
                            e.text.isNotBlank() -> e.text
                            e.summaryText.isNotBlank() -> e.summaryText
                            else -> e.reason
                        },
                        42
                    )
                    append("\n• ")
                    append(title)
                    if (body.isNotBlank()) {
                        append(": ")
                        append(body)
                    }
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(row.appName)
            .setMessage(msg)
            .setPositiveButton(getString(R.string.stats_open_blocked_messages)) { _, _ ->
                startActivity(
                    Intent(this, BlockedInboxActivity::class.java)
                        .putExtra(BlockedInboxActivity.EXTRA_APP_FILTER, row.packageName)
                )
            }
            .setNegativeButton(android.R.string.ok, null)
            .showAccented()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        b = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Mode is selected from StatisticsHubActivity (usage/runtime/blocking/other).
        mode = when (intent.getStringExtra(EXTRA_MODE)) {
            "runtime" -> Mode.USAGE
            "blocking" -> Mode.BLOCKING
            "other" -> Mode.OTHER
            else -> Mode.USAGE
        }

        EdgeToEdgeUtils.setupClassic(
            activity = this,
            toolbar = b.toolbar
        )

        // Keep status bar neutral (no accent bleed into system bar)
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
        // Match schedules: keep navigation bar dark so the system gesture/nav area reads as spacing
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.black)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false

        setSupportActionBar(b.toolbar)

        // Toolbar action: always-visible sort/filter button
        b.toolbar.inflateMenu(R.menu.menu_stats)
        tintToolbarIcons()
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort_filter -> {
                    showSortFilterMenu(b.toolbar)
                    true
                }
                else -> false
            }
        }
        val title = when (mode) {
            Mode.USAGE -> getString(R.string.stats_title)
            Mode.RUNTIME -> getString(R.string.stats_mode_runtime)
            Mode.BLOCKING -> getString(R.string.stats_mode_blocking)
            Mode.OTHER -> getString(R.string.stats_mode_other)
        }
        supportActionBar?.title = title
        b.toolbar.title = title

        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        // Background color changes after menu inflation -> ensure icons remain visible
        tintToolbarIcons()

        b.recycler.layoutManager = LinearLayoutManager(this)
        usageAdapter = StatsAdapter()
        // Blocking rows should open a dedicated detail screen (much nicer than a popup)
        blockAdapter = BlockStatsAdapter { row ->
            startActivity(
                Intent(this, BlockStatsDetailActivity::class.java)
                    .putExtra(BlockStatsDetailActivity.EXTRA_PKG, row.packageName)
                    .putExtra(BlockStatsDetailActivity.EXTRA_RANGE, range.name)
            )
        }
        runtimeAdapter = RuntimeBlockedAdapter()

        setupRangeChips()
        applyRangePremiumLock()

        // Sort/Filter entrypoints
        // 1) Always-visible icon next to the range chips (so users can't miss it)
        b.btnSortFilter.setOnClickListener { showSortFilterMenu(b.btnSortFilter) }
        // 2) Optional chip in the summary card (kept for convenience)
        b.chipSort.setOnClickListener { showSortFilterMenu(b.chipSort) }

        // Sort/filter makes sense for lists (usage/runtime/blocking). Keep it visible.
        b.btnSortFilter.isVisible = (mode != Mode.OTHER)
        b.chipSort.isVisible = (mode != Mode.OTHER)

        load()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Sort/Filter is shown via the bottom-right FAB (no toolbar action)
        return false
    }

    private fun tintToolbarIcons() {
        // On some themes/devices, menu/navigation icons stay black by default.
        // Use contrast against the *actual toolbar background* instead of relying on theme colorOnPrimary.
        val bg = AccentColor.getToolbarColor(this)
        val tint = if (MaterialColors.isColorLight(bg)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        b.toolbar.menu.forEach { it.icon?.mutate()?.setTint(tint) }
        b.toolbar.navigationIcon?.mutate()?.setTint(tint)
    }

    override fun onResume() {
        super.onResume()
        b.toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        applyRangePremiumLock()
        load()
    }

    private fun setupRangeChips() {
        fun setRange(target: Range, chipId: Int) {
            range = target
            syncRangeChipUi(chipId)
            load()
        }

        b.chipToday.setOnClickListener { setRange(Range.TODAY, b.chipToday.id) }
        b.chipWeek.setOnClickListener { setRange(Range.WEEK, b.chipWeek.id) }
        b.chipMonth.setOnClickListener { setRange(Range.MONTH, b.chipMonth.id) }
        b.chipYear.setOnClickListener { setRange(Range.YEAR, b.chipYear.id) }
        b.chipOverall.setOnClickListener { setRange(Range.OVERALL, b.chipOverall.id) }

        syncRangeChipUi(b.chipToday.id)
    }

    private fun syncRangeChipUi(activeChipId: Int) {
        val activeBg = AccentColor.getAccentColorInt(this)
        val activeText = if (MaterialColors.isColorLight(activeBg)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val inactiveBg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, 0)
        val inactiveText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, inactiveText)

        val chips = listOf(b.chipToday, b.chipWeek, b.chipMonth, b.chipYear, b.chipOverall)
        b.chipGroupRange.clearCheck()
        chips.forEach { chip ->
            val active = chip.id == activeChipId
            chip.isChecked = active
            chip.isCheckable = true
            chip.isClickable = true
            chip.isPressed = false
            chip.isSelected = false
            chip.isActivated = active
            chip.chipBackgroundColor = ColorStateList.valueOf(if (active) activeBg else inactiveBg)
            chip.setTextColor(if (active) activeText else inactiveText)
            chip.chipStrokeColor = ColorStateList.valueOf(if (active) activeBg else outline)
            chip.chipStrokeWidth = resources.displayMetrics.density
            chip.jumpDrawablesToCurrentState()
            chip.refreshDrawableState()
        }
        b.chipGroupRange.check(activeChipId)
    }

    /**
     * Non-premium should NOT be able to use WEEK/MONTH/YEAR/OVERALL.
     * We keep chips visible but disabled (common pattern).
     */
    private fun applyRangePremiumLock() {
        val premium = PremiumManager.isPremium(this)

        val locked = listOf(b.chipWeek, b.chipMonth, b.chipYear, b.chipOverall)
        locked.forEach { chip ->
            chip.isEnabled = premium
            chip.alpha = if (premium) 1.0f else 0.45f
        }

        // If user somehow got into a locked range (old state), snap back to TODAY.
        if (!premium && range != Range.TODAY) {
            range = Range.TODAY
            syncRangeChipUi(b.chipToday.id)
        } else {
            val currentChipId = when (range) {
                Range.TODAY -> b.chipToday.id
                Range.WEEK -> b.chipWeek.id
                Range.MONTH -> b.chipMonth.id
                Range.YEAR -> b.chipYear.id
                Range.OVERALL -> b.chipOverall.id
            }
            syncRangeChipUi(currentChipId)
        }
    }

    private fun cycleSort(withAnim: Boolean) {
        if (mode != Mode.USAGE) return

        if (withAnim) {
            val dx = 10f * resources.displayMetrics.density
            b.chipSort.animate()
                .translationXBy(dx)
                .setDuration(70)
                .withEndAction {
                    b.chipSort.animate()
                        .translationXBy(-dx * 2f)
                        .setDuration(90)
                        .withEndAction {
                            b.chipSort.animate()
                                .translationX(0f)
                                .setDuration(70)
                                .start()
                        }
                        .start()
                }
                .start()
        }

        // Quick toggle: primary metric <-> A–Z
        sort = when (sort) {
            Sort.NAME_AZ -> primarySortForMode()
            else -> Sort.NAME_AZ
        }
        applyAndShow()
    }

    private fun primarySortForMode(): Sort = when (mode) {
        // Blocking: focus on how often you tried while blocked.
        Mode.BLOCKING -> Sort.ATTEMPTS
        // Runtime: highlight where you tried most while blocked.
        Mode.RUNTIME -> Sort.ATTEMPTS
        else -> Sort.USED_TIME
    }

    private fun showSortFilterMenu(anchor: View) {
        if (mode == Mode.OTHER) return

        // PopupMenu looked "broken"/cramped on some devices. Use a clean Material dialog instead.
        val v = layoutInflater.inflate(R.layout.dialog_sort_filter, null)
        val rgFilter = v.findViewById<RadioGroup>(R.id.rgFilter)
        val rgSort = v.findViewById<RadioGroup>(R.id.rgSort)

        // Labels depend on screen
        val (sortDescLabel, sortAscLabel) = when (mode) {
            Mode.BLOCKING -> getString(R.string.stats_sort_attempts_desc) to getString(R.string.stats_sort_attempts_asc)
            // Runtime: sort by attempts.
            Mode.RUNTIME -> getString(R.string.stats_sort_attempts_desc) to getString(R.string.stats_sort_attempts_asc)
            else -> getString(R.string.stats_sort_used_time_desc) to getString(R.string.stats_sort_used_time_asc)
        }
        v.findViewById<RadioButton>(R.id.rbSortPrimaryDesc).text = sortDescLabel
        v.findViewById<RadioButton>(R.id.rbSortPrimaryAsc).text = sortAscLabel

        // Initial selections
        when (filter) {
            Filter.ALL_APPS -> rgFilter.check(R.id.rbFilterAll)
            Filter.BLOCKED_ONLY -> rgFilter.check(R.id.rbFilterBlocked)
        }

        when (sort) {
            Sort.NAME_AZ -> rgSort.check(R.id.rbSortAz)
            Sort.NAME_ZA -> rgSort.check(R.id.rbSortZa)
            Sort.ATTEMPTS, Sort.BLOCKED_TIME, Sort.USED_TIME -> {
                rgSort.check(if (sortDir == SortDir.ASC) R.id.rbSortPrimaryAsc else R.id.rbSortPrimaryDesc)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.stats_sort_filter_title))
            .setView(v)
            .setPositiveButton(getString(R.string.stats_apply)) { _, _ ->
                // Apply filter
                filter = when (rgFilter.checkedRadioButtonId) {
                    R.id.rbFilterBlocked -> Filter.BLOCKED_ONLY
                    else -> Filter.ALL_APPS
                }

                // Apply sort
                when (rgSort.checkedRadioButtonId) {
                    R.id.rbSortAz -> sort = Sort.NAME_AZ
                    R.id.rbSortZa -> sort = Sort.NAME_ZA
                    R.id.rbSortPrimaryAsc -> {
                        sort = primarySortForMode()
                        sortDir = SortDir.ASC
                    }
                    else -> { // primary desc
                        sort = primarySortForMode()
                        sortDir = SortDir.DESC
                    }
                }

                applyAndShow()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
    }

    private fun applyAndShow() {
        val pm = packageManager

        fun isLaunchable(pkg: String): Boolean {
            return runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }

        when (mode) {
            Mode.USAGE -> {
                val filtered = lastRows.asSequence()
                    .filter { row ->
                        when (filter) {
                            Filter.ALL_APPS -> true
                            Filter.BLOCKED_ONLY -> currentBlockedSet.contains(row.packageName)
                        }
                    }
                    .filter { row ->
                        // Hide non-launchable/internal packages by default (e.g. com.google.android.as), unless the user explicitly configured them (blocked/limited).
                        val launchable = isLaunchable(row.packageName)
                        launchable || currentBlockedSet.contains(row.packageName) || row.limitMinutes > 0
                    }
                    .toList()

                val sorted = when (sort) {
                    Sort.NAME_AZ -> filtered.sortedBy { it.appName.lowercase() }
                    Sort.NAME_ZA -> filtered.sortedByDescending { it.appName.lowercase() }
                    Sort.USED_TIME -> {
                        val cmp = when (sortDir) {
                            SortDir.DESC -> compareByDescending<StatsRow> { it.usedMsToday }
                            SortDir.ASC -> compareBy<StatsRow> { it.usedMsToday }
                        }
                        filtered.sortedWith(cmp.thenBy { it.appName.lowercase() })
                    }
                    else -> filtered
                }

                usageAdapter.submit(
                    sorted,
                    when (range) {
                        Range.TODAY -> StatsAdapter.RangeLabel.TODAY
                        Range.WEEK -> StatsAdapter.RangeLabel.WEEK
                        Range.MONTH -> StatsAdapter.RangeLabel.MONTH
                        Range.YEAR -> StatsAdapter.RangeLabel.YEAR
                        Range.OVERALL -> StatsAdapter.RangeLabel.OVERALL
                    }
                )
            }

            Mode.BLOCKING -> {
                val filtered = lastBlockRows.asSequence()
                    .filter { row ->
                        when (filter) {
                            Filter.ALL_APPS -> true
                            Filter.BLOCKED_ONLY -> currentBlockedSet.contains(row.packageName)
                        }
                    }
                    .filter { row ->
                        val launchable = isLaunchable(row.packageName)
                        launchable || currentBlockedSet.contains(row.packageName)
                    }
                    .toList()

                val sorted = when (sort) {
                    Sort.NAME_AZ -> filtered.sortedBy { it.appName.lowercase() }
                    Sort.NAME_ZA -> filtered.sortedByDescending { it.appName.lowercase() }
                    Sort.ATTEMPTS -> {
                        val cmp = when (sortDir) {
                            SortDir.DESC -> compareByDescending<BlockStatsRow> { it.attemptCount }
                            SortDir.ASC -> compareBy<BlockStatsRow> { it.attemptCount }
                        }
                        filtered.sortedWith(cmp.thenBy { it.appName.lowercase() })
                    }
                    else -> filtered
                }

                blockAdapter.submit(sorted)
            }

            Mode.RUNTIME -> {
                val filtered = lastRuntimeRows.asSequence()
                    .filter { row ->
                        when (filter) {
                            Filter.ALL_APPS -> true
                            Filter.BLOCKED_ONLY -> currentBlockedSet.contains(row.packageName)
                        }
                    }
                    .filter { row ->
                        val launchable = isLaunchable(row.packageName)
                        launchable || currentBlockedSet.contains(row.packageName)
                    }
                    .toList()

                val sorted = when (sort) {
                    Sort.NAME_AZ -> filtered.sortedBy { it.appName.lowercase() }
                    Sort.NAME_ZA -> filtered.sortedByDescending { it.appName.lowercase() }
                    Sort.BLOCKED_TIME -> {
                        val cmp = when (sortDir) {
                            SortDir.DESC -> compareByDescending<RuntimeBlockedRow> { it.scoreMs }
                            SortDir.ASC -> compareBy<RuntimeBlockedRow> { it.scoreMs }
                        }
                        filtered.sortedWith(cmp.thenBy { it.appName.lowercase() })
                    }
                    else -> filtered
                }

                runtimeAdapter.submit(sorted)
            }

            Mode.OTHER -> Unit
        }

        // Update the hint chip if it's visible.
        val sortLabel = when (sort) {
            Sort.NAME_AZ -> getString(R.string.stats_sort_az)
            Sort.NAME_ZA -> getString(R.string.stats_sort_za)
            Sort.USED_TIME -> when (sortDir) {
                SortDir.DESC -> getString(R.string.stats_sort_used_time_desc)
                SortDir.ASC -> getString(R.string.stats_sort_used_time_asc)
            }
            Sort.ATTEMPTS -> when (sortDir) {
                SortDir.DESC -> getString(R.string.stats_sort_attempts_desc)
                SortDir.ASC -> getString(R.string.stats_sort_attempts_asc)
            }
            Sort.BLOCKED_TIME -> when (sortDir) {
                SortDir.DESC -> getString(R.string.stats_sort_blocked_time_desc)
                SortDir.ASC -> getString(R.string.stats_sort_blocked_time_asc)
            }
        }

        val filterLabel = when (filter) {
            Filter.ALL_APPS -> getString(R.string.stats_filter_all_apps)
            Filter.BLOCKED_ONLY -> getString(R.string.stats_filter_blocked_only)
        }

        b.chipSort.text = getString(R.string.stats_sort_hint_fmt, "$filterLabel • $sortLabel")
    }

    private fun load() {
		val profile = ProfileStore.getCurrent(this)

		// Keep the blocked set available for ALL modes (Blocking/Runtime/Other also use it for filtering).
		// ProfileStore.getBlockedForProfile expects a non-null profile id.
		currentBlockedSet = if (profile.isNullOrBlank()) {
			emptySet()
		} else {
			ProfileStore.getBlockedForProfile(this, profile).toSet()
		}

        applyRangePremiumLock()

        if (profile.isNullOrBlank()) {
            b.recycler.adapter = usageAdapter
            b.chipSort.visibility = View.VISIBLE

            b.emptyText.visibility = View.VISIBLE
            b.emptyText.gravity = Gravity.CENTER
            b.emptyText.text = getString(R.string.select_profile_first)

            lastRows = emptyList()
            usageAdapter.submit(emptyList(), StatsAdapter.RangeLabel.TODAY)

            b.recycler.visibility = View.GONE
            b.cardInfo.visibility = View.GONE
            b.cardInsights.visibility = View.GONE
            b.tvListHeader.visibility = View.GONE
            return
        }

        // attach correct adapter per mode
        when (mode) {
            Mode.USAGE -> {
                b.recycler.adapter = usageAdapter
                b.recycler.visibility = View.VISIBLE
                b.chipSort.visibility = View.VISIBLE
            }
            Mode.BLOCKING -> {
                b.recycler.adapter = blockAdapter
                b.recycler.visibility = View.VISIBLE
                b.chipSort.visibility = View.GONE
            }
            Mode.RUNTIME -> {
                b.recycler.adapter = runtimeAdapter
                b.recycler.visibility = View.VISIBLE
                b.chipSort.visibility = View.GONE
            }
            Mode.OTHER -> {
                b.recycler.visibility = View.GONE
                b.chipSort.visibility = View.GONE
            }
        }

        val modeSnapshot = mode
        val rangeSnapshot = range

        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val computed = withContext(Dispatchers.Default) {
                computeForMode(profile, modeSnapshot, rangeSnapshot)
            }

            // If something changed while we were computing, avoid applying stale UI.
            if (mode != modeSnapshot || range != rangeSnapshot) return@launch
            if (ProfileStore.getCurrent(this@StatsActivity) != profile) return@launch

            // reset common UI
            b.cardInfo.visibility = View.GONE
            b.cardInsights.visibility = View.GONE
            b.emptyText.visibility = View.GONE
            b.tvListHeader.visibility = View.GONE
            b.tvInfoDetails.visibility = View.VISIBLE
            b.llInfoRows.visibility = View.GONE
            b.tvInfoNote.visibility = View.GONE

            when (computed) {
                is UsageComputed -> {
                    lastRows = computed.rows
                    // already set at start of load()

                    b.tvTodayTitle.text = rangeLabel()
                    b.tvTodaySubtitle.visibility = if (range == Range.TODAY) View.VISIBLE else View.GONE
                    if (range == Range.TODAY) b.tvTodaySubtitle.text = getString(R.string.stats_today_subtitle)

                    // Insights card (premium only)
                    val ins = computed.insights
                    if (ins == null) {
                        b.cardInsights.visibility = View.GONE
                    } else {
                        b.cardInsights.visibility = View.VISIBLE
                        b.tvInsightsTrend.text = ins.trend
                        b.tvInsightsRatio.text = ins.ratio
                        b.tvInsightsImproved.text = ins.improved
                    }

                    applyAndShow()
                }

                is BlockingComputed -> {
                    b.tvTodayTitle.text = getString(R.string.stats_mode_blocking)
                    b.tvTodaySubtitle.visibility = View.VISIBLE
                    b.tvTodaySubtitle.text = getString(R.string.stats_blocking_subtitle)

                    // Summary card: blocked messages (we intentionally don't surface "attempts" anymore)
                    b.cardInfo.visibility = View.VISIBLE
                    b.tvInfoLabel.text = rangeLabel()
                    b.tvInfoPrimary.text = NumberFormat.getInstance().format(computed.blockedMessages)
                    b.tvInfoSecondary.text = getString(R.string.stats_blocking_primary_caption)

                    b.tvInfoDetails.visibility = View.VISIBLE
                    b.tvInfoDetails.text = getString(
                        R.string.stats_blocking_details_fmt,
                        computed.blockedMessages
                    )

                    // "Top blocked apps" header above list
                    b.tvListHeader.visibility = if (computed.rows.isEmpty()) View.GONE else View.VISIBLE
                    if (!computed.rows.isEmpty()) {
                        b.tvListHeader.text = getString(R.string.stats_runtime_top_blocked)
                    }

                    b.emptyText.isVisible = computed.rows.isEmpty()
                    if (b.emptyText.isVisible) {
                        b.emptyText.gravity = Gravity.CENTER
                        b.emptyText.text = getString(R.string.stats_blocking_empty)
                    }

                    lastBlockRows = computed.rows
                    applyAndShow()
                }

                is RuntimeComputed -> {
                    b.tvTodayTitle.text = getString(R.string.stats_mode_runtime)
                    b.tvTodaySubtitle.visibility = View.VISIBLE
                    b.tvTodaySubtitle.text = getString(R.string.stats_runtime_subtitle)

                    lastRuntimeRows = computed.rows
                    applyAndShow()

                    b.emptyText.isVisible = computed.rows.isEmpty() && computed.runtimeMs <= 0L
                    if (b.emptyText.isVisible) {
                        b.emptyText.gravity = Gravity.CENTER
                        b.emptyText.text = statsEmptyMessage()
                    }

                    // Info card shows runtime summary
                    b.cardInfo.visibility = View.VISIBLE
                    b.tvInfoLabel.text = rangeLabel()
                    b.tvInfoPrimary.text = prettyMs(computed.runtimeMs)
                    b.tvInfoSecondary.text = getString(R.string.stats_runtime_secondary_caption)

                    // Move "Top blocked apps" OUT of the box:
                    b.tvInfoDetails.visibility = View.GONE
                    b.tvListHeader.visibility = View.VISIBLE
                    b.tvListHeader.text = getString(R.string.stats_runtime_top_blocked)
                }

                is OtherComputed -> {
                    b.tvTodayTitle.text = getString(R.string.stats_mode_other)
                    b.tvTodaySubtitle.visibility = View.VISIBLE
                    b.tvTodaySubtitle.text = getString(R.string.stats_other_subtitle)

                    // "Other" tiles live inside the info card section in the layout.
                    // Keep the card visible, but hide the big header fields so we don't show a generic "total" summary above the tiles.
                    b.cardInfo.visibility = View.VISIBLE
                    b.tvInfoLabel.visibility = View.GONE
                    b.tvInfoPrimary.visibility = View.GONE
                    b.tvInfoSecondary.visibility = View.GONE
                    b.tvInfoDetails.visibility = View.GONE

                    b.llInfoRows.visibility = View.VISIBLE
                    b.tvInfoNote.visibility = View.VISIBLE
                    b.tvInfoNote.text = getString(R.string.stats_other_note_totals)

                    // Hide optional tiles when the feature isn't enabled or has never been used.
                    // NOTE: we are inside a coroutine scope here, so `this` would refer to CoroutineScope.
                    // We need the Activity (Context) instance.
                    val qrEnabled = at.saltyy.switchly.data.prefs.AutomationModeStore.isQrAllowed(this@StatsActivity)
                    val showQr = qrEnabled && (computed.qrTotal > 0 || computed.qrScans > 0)
                    b.cardOtherQr.visibility = if (showQr) View.VISIBLE else View.GONE

                    val showTemp = (computed.tempTotal > 0 || computed.tempEnables > 0)
                    b.cardOtherTempEnable.visibility = if (showTemp) View.VISIBLE else View.GONE

                    val showLimits = (computed.limitsTotal > 0 || computed.limitsReached > 0)
                    b.cardOtherLimitReached.visibility = if (showLimits) View.VISIBLE else View.GONE

                    val showAttempts = (computed.blockedAttemptsTotal > 0 || computed.blockedAttempts > 0)
                    b.cardOtherAttempts.visibility = if (showAttempts) View.VISIBLE else View.GONE

                    b.tvOtherEmergencyCount.text = NumberFormat.getInstance().format(computed.emergencyUsed)
                    b.tvOtherNfcCount.text = NumberFormat.getInstance().format(computed.nfcUsed)
                    b.tvOtherSchedulesCount.text = NumberFormat.getInstance().format(computed.schedulesExecuted)
                    b.tvOtherQrCount.text = NumberFormat.getInstance().format(computed.qrScans)
                    b.tvOtherTempEnableCount.text = NumberFormat.getInstance().format(computed.tempEnables)
                    b.tvOtherLimitReachedCount.text = NumberFormat.getInstance().format(computed.limitsReached)
                    b.tvOtherBlockedAppsCount.text = NumberFormat.getInstance().format(computed.blockedAppsNow)
                    b.tvOtherAttemptsCount.text = NumberFormat.getInstance().format(computed.blockedAttempts)

                    b.tvOtherProfilesCount.text = NumberFormat.getInstance().format(computed.profilesCount)
                    b.tvOtherSchedulesEnabledCount.text = NumberFormat.getInstance().format(computed.enabledSchedules)
                    b.tvOtherLimitedAppsCount.text = NumberFormat.getInstance().format(computed.limitedApps)
                    b.tvOtherEmergencyTotal.text = getString(
                        R.string.stats_other_total_fmt,
                        NumberFormat.getInstance().format(computed.emergencyTotal)
                    )
                    b.tvOtherNfcTotal.text = getString(
                        R.string.stats_other_total_fmt,
                        NumberFormat.getInstance().format(computed.nfcTotal)
                    )
                    b.tvOtherSchedulesTotal.text = getString(
                        R.string.stats_other_total_fmt,
                        NumberFormat.getInstance().format(computed.schedulesTotal)
                    )
                    b.tvOtherQrTotal.text = getString(
                        R.string.stats_other_total_fmt,
                        NumberFormat.getInstance().format(computed.qrTotal)
                    )
                    b.tvOtherTempEnableTotal.text = getString(
                        R.string.stats_other_total_fmt,
                        NumberFormat.getInstance().format(computed.tempTotal)
                    )
                    b.tvOtherLimitReachedTotal.text = getString(
                        R.string.stats_other_total_fmt,
                        NumberFormat.getInstance().format(computed.limitsTotal)
                    )
                    b.tvOtherBlockedAppsTotal.text = getString(
                        R.string.stats_other_total_fmt,
                        NumberFormat.getInstance().format(computed.blockedAppsTotal)
                    )

                    b.tvOtherAttemptsTotal.text = getString(
                        R.string.stats_other_total_fmt,
                        NumberFormat.getInstance().format(computed.blockedAttemptsTotal)
                    )
                    b.tvOtherProfilesTotal.text = getString(
                        R.string.stats_other_total_fmt,
                        NumberFormat.getInstance().format(computed.profilesTotal)
                    )
                    b.tvOtherSchedulesEnabledTotal.text = getString(
                        R.string.stats_other_total_fmt,
                        NumberFormat.getInstance().format(computed.enabledSchedulesTotal)
                    )
                    b.tvOtherLimitedAppsTotal.text = getString(
                        R.string.stats_other_total_fmt,
                        NumberFormat.getInstance().format(computed.limitedAppsTotal)
                    )

                    balanceOtherGrid(b.gridOtherTiles, b.otherGridSpacer)

                    val isQrEmpty = !showQr || computed.qrScans == 0
                    val isTempEmpty = !showTemp || computed.tempEnables == 0
                    val isLimitsEmpty = !showLimits || computed.limitsReached == 0
                    b.emptyText.isVisible = (
                        computed.emergencyUsed == 0 &&
                            computed.nfcUsed == 0 &&
                            computed.schedulesExecuted == 0 &&
                            isQrEmpty &&
                            isTempEmpty &&
                            isLimitsEmpty &&
                            computed.blockedAppsNow == 0
                        )
                    if (b.emptyText.isVisible) {
                        b.emptyText.gravity = Gravity.CENTER
                        b.emptyText.text = statsEmptyMessage()
                    }
                }
            }
        }
    }

    // The previous text ("No app limits …") was misleading when users DO have limits, but simply haven't generated stats in the selected range yet.
    private fun statsEmptyMessage(): String {
        val hasAnyLimits = runCatching {
            UsageLimitStore
                .getAllLimitedPackagesAnyProfile(this)
                .isNotEmpty()
        }.getOrDefault(false) || runCatching {
            at.saltyy.switchly.data.prefs.DomainLimitStore
                .getDomainsWithLimit(this)
                .isNotEmpty()
        }.getOrDefault(false)

        return if (hasAnyLimits) {
            getString(R.string.stats_empty_no_data)
        } else {
            getString(R.string.stats_empty_no_limits)
        }
    }

    private fun balanceOtherGrid(grid: android.widget.GridLayout, spacer: View) {
        // GridLayout keeps column positions when some children are GONE, which can look like "2 left/3 right" with holes. 
        // Instead of spanning tiles, we add a small invisible spacer tile to keep the total visible item count even.
        val visibleCount = (0 until grid.childCount)
            .map { grid.getChildAt(it) }
            .count { it.isVisible && it.id != spacer.id }

        spacer.isVisible = visibleCount % 2 == 1
    }

    private fun computeForMode(profile: String, mode: Mode, range: Range): ComputedBase {
        val pm = packageManager

        val calNow = Calendar.getInstance()
        val weekDays = weekToDateDays(calNow)
        val yearNow = calNow.get(Calendar.YEAR)
        val monthNow1 = calNow.get(Calendar.MONTH) + 1

        val overallAgg: OverallAgg? = if (range == Range.OVERALL) buildOverallAgg() else null

        val baseStatPkgs: Set<String> = if (overallAgg != null) {
            buildSet {
                addAll(overallAgg.usageMs.keys)
                addAll(overallAgg.blockedMs.keys)
                addAll(overallAgg.blockedCount.keys)
                addAll(overallAgg.attemptCount.keys)
            }
        } else {
            getAllPkgsWithAnyUsageRecords()
        }

        val pkgs = buildSet {
            addAll(baseStatPkgs)
            addAll(UsageLimitStore.getAllLimitedPackages(this@StatsActivity, profile))
            addAll(UsageLimitStore.getAllEverLimitedPackages(this@StatsActivity))
            addAll(UsageLimitStore.getAllLimitedPackagesAnyProfile(this@StatsActivity))
            addAll(ProfileStore.getBlockedForProfile(this@StatsActivity, profile))
        }.toList().filter { isInstalled(it) }.sorted()

        val everLimitedSet = UsageLimitStore.getAllEverLimitedPackages(this).toHashSet()
        val blockedAlwaysSet = ProfileStore.getBlockedForProfile(this@StatsActivity, profile).toHashSet()

        fun usageMsFor(pkg: String): Long = when (range) {
            Range.TODAY -> AppUsageToday.getUsageMsToday(this, pkg)
            Range.WEEK -> UsageStore.getUsageMsForLastNDays(this, pkg, weekDays)
            Range.MONTH -> UsageStore.getUsageMsForMonth(this, pkg, yearNow, monthNow1)
            Range.YEAR -> UsageStore.getUsageMsForYear(this, pkg, yearNow)
            Range.OVERALL -> overallAgg?.usageMs?.get(pkg) ?: 0L
        }

        fun blockedMsFor(pkg: String): Long = when (range) {
            Range.TODAY -> BlockedTimeStore.getBlockedMsToday(this, pkg)
            Range.WEEK -> BlockedTimeStore.getBlockedMsForLastNDays(this, pkg, weekDays)
            Range.MONTH -> BlockedTimeStore.getBlockedMsForMonth(this, pkg, yearNow, monthNow1)
            Range.YEAR -> BlockedTimeStore.getBlockedMsForYear(this, pkg, yearNow)
            Range.OVERALL -> overallAgg?.blockedMs?.get(pkg) ?: 0L
        }

        fun blockedCountFor(pkg: String): Int = when (range) {
            Range.TODAY -> BlockCountStore.getToday(this, pkg)
            Range.WEEK -> BlockCountStore.getForLastNDays(this, pkg, weekDays)
            Range.MONTH -> BlockCountStore.getForMonth(this, pkg, yearNow, monthNow1)
            Range.YEAR -> BlockCountStore.getForYear(this, pkg, yearNow)
            Range.OVERALL -> overallAgg?.blockedCount?.get(pkg) ?: 0
        }

        fun attemptsFor(pkg: String): Int = when (range) {
            Range.TODAY -> BlockAttemptStore.getToday(this, pkg)
            Range.WEEK -> BlockAttemptStore.getForLastNDays(this, pkg, weekDays)
            Range.MONTH -> BlockAttemptStore.getForMonth(this, pkg, yearNow, monthNow1)
            Range.YEAR -> BlockAttemptStore.getForYear(this, pkg, yearNow)
            Range.OVERALL -> overallAgg?.attemptCount?.get(pkg) ?: 0
        }

        return when (mode) {
            Mode.USAGE -> {
                val rows = pkgs.map { pkg ->
                    val label = resolveLabel(pm, pkg)

                    val usedMs = usageMsFor(pkg)
                    val blockedMs = blockedMsFor(pkg)

                    val limitMin = if (range == Range.TODAY) {
                        max(
                            UsageLimitStore.getLimitMinutes(this, profile, pkg),
                            UsageLimitStore.getBestLimitMinutesAcrossProfiles(this, pkg)
                        )
                    } else 0

                    StatsRow(
                        packageName = pkg,
                        appName = label,
                        limitMinutes = limitMin,
                        usedMsToday = usedMs,
                        blockedMsToday = blockedMs
                    )
                }.filter { row ->
                    val everLimited = everLimitedSet.contains(row.packageName)
                    (row.usedMsToday > 0L) || (row.blockedMsToday > 0L) || (row.limitMinutes > 0) || everLimited
                }

                val insights = computeInsightsUi(
                    profile = profile,
                    blockedAlwaysSet = blockedAlwaysSet,
                    pm = pm,
                    yearNow = yearNow,
                    monthNow1 = monthNow1,
                    weekDays = weekDays,
                    rows = rows,
                    range = range
                )

                UsageComputed(
                    pkgs = pkgs,
                    weekDays = weekDays,
                    yearNow = yearNow,
                    monthNow1 = monthNow1,
                    rows = rows,
                    insights = insights
                )
            }

            Mode.BLOCKING -> {
                val rows = pkgs.map { pkg ->
                    BlockStatsRow(
                        packageName = pkg,
                        appName = resolveLabel(pm, pkg),
                        blockedMs = blockedMsFor(pkg),
                        blockedCount = blockedCountFor(pkg),
                        attemptCount = attemptsFor(pkg)
                    )
                }.filter { it.blockedMs > 0L || it.blockedCount > 0 || it.attemptCount > 0 }
                    .sortedWith(compareByDescending<BlockStatsRow> { it.attemptCount }.thenBy { it.appName.lowercase() })

                val totalBlockedMs = rows.sumOf { it.blockedMs }
                val totalBlocks = rows.sumOf { it.blockedCount }
                val totalAttempts = rows.sumOf { it.attemptCount }
                // Show all-time inbox count (not just the selected range)
                val blockedMessages = blockedInboxCountOverall(profile)

                BlockingComputed(
                    pkgs = pkgs,
                    weekDays = weekDays,
                    yearNow = yearNow,
                    monthNow1 = monthNow1,
                    totalBlockedMs = totalBlockedMs,
                    totalBlocks = totalBlocks,
                    totalAttempts = totalAttempts,
                    blockedMessages = blockedMessages,
                    rows = rows
                )
            }

            Mode.RUNTIME -> {
                val runtimeMs = when (range) {
                    Range.TODAY -> SwitchlyRuntimeStore.getRuntimeMsToday(this)
                    Range.WEEK -> SwitchlyRuntimeStore.getRuntimeMsForLastNDays(this, weekDays)
                    Range.MONTH -> SwitchlyRuntimeStore.getRuntimeMsForMonth(this, yearNow, monthNow1)
                    Range.YEAR -> SwitchlyRuntimeStore.getRuntimeMsForYear(this, yearNow)
                    Range.OVERALL -> SwitchlyRuntimeStore.getRuntimeMsOverall(this)
                }

                val rows = pkgs.mapNotNull { pkg ->
                    val attempts = attemptsFor(pkg)

                    // Runtime view: focus on "how often you tried while blocked" (attempts).
                    // Blocked time is noisy/unreliable in this app's flow, so we don't surface it here.
                    if (attempts <= 0) return@mapNotNull null

                    RuntimeBlockedRow(
                        packageName = pkg,
                        appName = resolveLabel(pm, pkg),
                        blockedMs = 0L,
                        blockedCount = 0,
                        attemptCount = attempts,
                        scoreMs = attempts.toLong()
                    )
                }.sortedWith(
                    compareByDescending<RuntimeBlockedRow> { it.attemptCount }
                        .thenBy { it.appName.lowercase() }
                )

                val totalBlockedMs = 0L

                RuntimeComputed(
                    pkgs = pkgs,
                    weekDays = weekDays,
                    yearNow = yearNow,
                    monthNow1 = monthNow1,
                    runtimeMs = runtimeMs,
                    totalBlockedMs = totalBlockedMs,
                    rows = rows
                )
            }

            Mode.OTHER -> {
                val emergencyUsed = when (range) {
                    Range.TODAY -> at.saltyy.switchly.data.prefs.EmergencyUnlockCountStore.getToday(this)
                    Range.WEEK -> at.saltyy.switchly.data.prefs.EmergencyUnlockCountStore.getForLastNDays(this, weekDays)
                    Range.MONTH -> at.saltyy.switchly.data.prefs.EmergencyUnlockCountStore.getForMonth(this, yearNow, monthNow1)
                    Range.YEAR -> at.saltyy.switchly.data.prefs.EmergencyUnlockCountStore.getForYear(this, yearNow)
                    Range.OVERALL -> at.saltyy.switchly.data.prefs.EmergencyUnlockCountStore.getOverall(this)
                }

                val emergencyTotal = at.saltyy.switchly.data.prefs.EmergencyUnlockCountStore.getOverall(this)

                val nfcUsed = when (range) {
                    Range.TODAY -> NfcScanCountStore.getToday(this)
                    Range.WEEK -> NfcScanCountStore.getForLastNDays(this, weekDays)
                    Range.MONTH -> NfcScanCountStore.getForMonth(this, yearNow, monthNow1)
                    Range.YEAR -> NfcScanCountStore.getForYear(this, yearNow)
                    Range.OVERALL -> NfcScanCountStore.getOverall(this)
                }

                val nfcTotal = NfcScanCountStore.getOverall(this)

                val schedulesExecuted = when (range) {
                    Range.TODAY -> ScheduleExecutionCountStore.getToday(this)
                    Range.WEEK -> ScheduleExecutionCountStore.getForLastNDays(this, weekDays)
                    Range.MONTH -> ScheduleExecutionCountStore.getForMonth(this, yearNow, monthNow1)
                    Range.YEAR -> ScheduleExecutionCountStore.getForYear(this, yearNow)
                    Range.OVERALL -> ScheduleExecutionCountStore.getOverall(this)
                }

                val schedulesTotal = ScheduleExecutionCountStore.getOverall(this)

                val qrScans = when (range) {
                    Range.TODAY -> at.saltyy.switchly.data.prefs.QrScanCountStore.getToday(this)
                    Range.WEEK -> at.saltyy.switchly.data.prefs.QrScanCountStore.getForLastNDays(this, weekDays)
                    Range.MONTH -> at.saltyy.switchly.data.prefs.QrScanCountStore.getForMonth(this, yearNow, monthNow1)
                    Range.YEAR -> at.saltyy.switchly.data.prefs.QrScanCountStore.getForYear(this, yearNow)
                    Range.OVERALL -> at.saltyy.switchly.data.prefs.QrScanCountStore.getOverall(this)
                }

                val qrTotal = at.saltyy.switchly.data.prefs.QrScanCountStore.getOverall(this)

                val tempEnables = when (range) {
                    Range.TODAY -> at.saltyy.switchly.data.prefs.TempEnableCountStore.getToday(this)
                    Range.WEEK -> at.saltyy.switchly.data.prefs.TempEnableCountStore.getForLastNDays(this, weekDays)
                    Range.MONTH -> at.saltyy.switchly.data.prefs.TempEnableCountStore.getForMonth(this, yearNow, monthNow1)
                    Range.YEAR -> at.saltyy.switchly.data.prefs.TempEnableCountStore.getForYear(this, yearNow)
                    Range.OVERALL -> at.saltyy.switchly.data.prefs.TempEnableCountStore.getOverall(this)
                }

                val tempTotal = at.saltyy.switchly.data.prefs.TempEnableCountStore.getOverall(this)

                val limitsReached = when (range) {
                    Range.TODAY -> at.saltyy.switchly.data.prefs.LimitHitCountStore.getToday(this)
                    Range.WEEK -> at.saltyy.switchly.data.prefs.LimitHitCountStore.getForLastNDays(this, weekDays)
                    Range.MONTH -> at.saltyy.switchly.data.prefs.LimitHitCountStore.getForMonth(this, yearNow, monthNow1)
                    Range.YEAR -> at.saltyy.switchly.data.prefs.LimitHitCountStore.getForYear(this, yearNow)
                    Range.OVERALL -> at.saltyy.switchly.data.prefs.LimitHitCountStore.getOverall(this)
                }

                val limitsTotal = at.saltyy.switchly.data.prefs.LimitHitCountStore.getOverall(this)

                val profilesCount = ProfileStore.getProfiles(this).size
                val profilesTotal = profilesCount

                val enabledSchedules = at.saltyy.switchly.data.prefs.ScheduleStore.getAll(this)
                    .count { it.enabled && it.profile == profile }
                val enabledSchedulesTotal = enabledSchedules

                val limitedApps = UsageLimitStore.getAllLimitedPackagesAnyProfile(this).size
                val limitedAppsTotal = limitedApps


                val blockedAppsNow = pkgs.count { pkg ->
                    blockedMsFor(pkg) > 0L || blockedCountFor(pkg) > 0 || attemptsFor(pkg) > 0
                }

                val blockedAppsTotal: Int = if (range == Range.OVERALL) {
                    blockedAppsNow
                } else {
                    val oa = buildOverallAgg()
                    if (oa == null) 0 else pkgs.count { pkg ->
                        (oa.blockedMs[pkg] ?: 0L) > 0L ||
                            (oa.blockedCount[pkg] ?: 0) > 0 ||
                            (oa.attemptCount[pkg] ?: 0) > 0
                    }
                }

                val blockedAttempts = when (range) {
                    Range.TODAY -> BlockAttemptStore.getTodayTotal(this)
                    Range.WEEK -> BlockAttemptStore.getForLastNDaysTotal(this, weekDays)
                    Range.MONTH -> BlockAttemptStore.getForMonthTotal(this, yearNow, monthNow1)
                    Range.YEAR -> BlockAttemptStore.getForYearTotal(this, yearNow)
                    Range.OVERALL -> BlockAttemptStore.getOverallTotal(this)
                }

                val blockedAttemptsTotal = BlockAttemptStore.getOverallTotal(this)

                OtherComputed(
                    pkgs = pkgs,
                    weekDays = weekDays,
                    yearNow = yearNow,
                    monthNow1 = monthNow1,
                    emergencyUsed = emergencyUsed,
                    emergencyTotal = emergencyTotal,
                    nfcUsed = nfcUsed,
                    nfcTotal = nfcTotal,
                    schedulesExecuted = schedulesExecuted,
                    schedulesTotal = schedulesTotal,
                    qrScans = qrScans,
                    qrTotal = qrTotal,
                    tempEnables = tempEnables,
                    tempTotal = tempTotal,
                    limitsReached = limitsReached,
                    limitsTotal = limitsTotal,
                    profilesCount = profilesCount,
                    profilesTotal = profilesTotal,
                    enabledSchedules = enabledSchedules,
                    enabledSchedulesTotal = enabledSchedulesTotal,
                    limitedApps = limitedApps,
                    limitedAppsTotal = limitedAppsTotal,
                    blockedAppsNow = blockedAppsNow,
                    blockedAppsTotal = blockedAppsTotal,
                    blockedAttempts = blockedAttempts,
                    blockedAttemptsTotal = blockedAttemptsTotal
                )
            }
        }
    }

    private fun computeInsightsUi(
        profile: String,
        blockedAlwaysSet: Set<String>,
        pm: PackageManager,
        yearNow: Int,
        monthNow1: Int,
        weekDays: Int,
        rows: List<StatsRow>,
        range: Range
    ): InsightsUi? {
        // Premium-only feature
        if (!PremiumManager.isPremium(this)) return null

        // managed apps only
        val managed = HashSet<String>().apply {
            addAll(blockedAlwaysSet)
            addAll(UsageLimitStore.getAllLimitedPackagesAnyProfile(this@StatsActivity))
            addAll(UsageLimitStore.getAllLimitedPackages(this@StatsActivity, profile))
        }

        val managedRows = rows.filter { managed.contains(it.packageName) }

        val usedTotal = managedRows.sumOf { it.usedMsToday.coerceAtLeast(0L) }
        val overLimitTotal = managedRows.sumOf { row ->
            val pkg = row.packageName
            val usedMs = row.usedMsToday.coerceAtLeast(0L)

            val allowedMs = when {
                blockedAlwaysSet.contains(pkg) -> 0L
                UsageLimitStore.getLimitMinutes(this, profile, pkg) > 0 ->
                    UsageLimitStore.getLimitMinutes(this, profile, pkg) * 60_000L
                else -> Long.MAX_VALUE
            }

            if (allowedMs == Long.MAX_VALUE) 0L else max(0L, usedMs - allowedMs)
        }

        val ratioLine = if (usedTotal <= 0L) {
            ""
        } else {
            val denom = usedTotal.coerceAtLeast(1L)
            val percent = ((overLimitTotal.toDouble()/denom.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            if (percent == 0) getString(R.string.stats_insights_ratio_none)
            else getString(R.string.stats_insights_ratio_fmt, percent)
        }

        val trendLine = when (range) {
            Range.WEEK -> {
                val current = managed.sumOf { UsageStore.getUsageMsForPastRange(this, it, 0, weekDays) }
                val prev = managed.sumOf { UsageStore.getUsageMsForPastRange(this, it, weekDays, weekDays) }
                formatTrend(current, prev)
            }
            Range.MONTH -> {
                val current = managed.sumOf { UsageStore.getUsageMsForMonth(this, it, yearNow, monthNow1) }
                val (py, pm1) = prevMonth(yearNow, monthNow1)
                val prev = managed.sumOf { UsageStore.getUsageMsForMonth(this, it, py, pm1) }
                formatTrend(current, prev)
            }
            Range.YEAR -> {
                val current = managed.sumOf { UsageStore.getUsageMsForYear(this, it, yearNow) }
                val prev = managed.sumOf { UsageStore.getUsageMsForYear(this, it, yearNow - 1) }
                formatTrend(current, prev)
            }
            else -> getString(R.string.stats_insights_trend_no_data)
        }

        val improvedLine = when (range) {
            Range.WEEK -> mostImprovedForWeek(managed.toList(), pm, weekDays)
            Range.MONTH -> mostImprovedForMonth(managed.toList(), pm, yearNow, monthNow1)
            Range.YEAR -> mostImprovedForYear(managed.toList(), pm, yearNow)
            else -> getString(R.string.stats_insights_improved_none)
        }

        return InsightsUi(trend = trendLine, ratio = ratioLine, improved = improvedLine)
    }

    private fun buildOverallAgg(): OverallAgg {
        // Ensure buffered usage/blocked-time deltas are included.
        UsageStore.flush(this)
        BlockedTimeStore.flush(this)

        val sp = getSharedPreferences("switchly_prefs", MODE_PRIVATE)

        val usage = HashMap<String, Long>()
        val blockedMs = HashMap<String, Long>()
        val blockedCount = HashMap<String, Int>()
        val attempts = HashMap<String, Int>()

        fun readLong(vAny: Any?): Long {
            return when (vAny) {
                is Long -> vAny
                is Int -> vAny.toLong()
                is Float -> vAny.toLong()
                is Double -> vAny.toLong()
                is Number -> vAny.toLong()
                is String -> vAny.toLongOrNull() ?: 0L
                else -> 0L
            }
        }

        for ((k, vAny) in sp.all) {
            val v = readLong(vAny)
            if (v <= 0L) continue

            when {
                k.startsWith("usage_day_") -> {
                    val rest = k.removePrefix("usage_day_")
                    val idx = rest.indexOf('_')
                    if (idx <= 0 || idx >= rest.length - 1) continue
                    val pkg = rest.substring(idx + 1)
                    usage[pkg] = (usage[pkg] ?: 0L) + v
                }
                k.startsWith("blocked_ms_") -> {
                    val rest = k.removePrefix("blocked_ms_")
                    val idx = rest.indexOf('_')
                    if (idx <= 0 || idx >= rest.length - 1) continue
                    val pkg = rest.substring(idx + 1)
                    blockedMs[pkg] = (blockedMs[pkg] ?: 0L) + v
                }
                k.startsWith("blocked_count_") -> {
                    val rest = k.removePrefix("blocked_count_")
                    val idx = rest.indexOf('_')
                    if (idx <= 0 || idx >= rest.length - 1) continue
                    val pkg = rest.substring(idx + 1)
                    blockedCount[pkg] = (blockedCount[pkg] ?: 0) + v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                k.startsWith("blocked_attempt_") -> {
                    val rest = k.removePrefix("blocked_attempt_")
                    val idx = rest.indexOf('_')
                    if (idx <= 0 || idx >= rest.length - 1) continue
                    val pkg = rest.substring(idx + 1)
                    attempts[pkg] = (attempts[pkg] ?: 0) + v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
            }
        }

        return OverallAgg(
            usageMs = usage,
            blockedMs = blockedMs,
            blockedCount = blockedCount,
            attemptCount = attempts
        )
    }

    private fun rangeLabel(): String {
        return when (range) {
            Range.TODAY -> getString(R.string.stats_range_today)
            Range.WEEK -> getString(R.string.stats_range_week)
            Range.MONTH -> getString(R.string.stats_range_month)
            Range.YEAR -> getString(R.string.stats_range_year)
            Range.OVERALL -> getString(R.string.stats_range_overall)
        }
    }

    private fun prettyMs(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalSec = (ms/1000L).toInt()
        val h = totalSec/3600
        val m = (totalSec % 3600)/60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm".format(m)
    }

    private fun prettyMinutesShort(ms: Long): String {
        val m = (ms/60_000L).toInt().coerceAtLeast(0)
        return "${m}m"
    }

    private fun getAllPkgsWithAnyUsageRecords(): Set<String> {
        val sp = getSharedPreferences("switchly_prefs", MODE_PRIVATE)
        val prefixes = arrayOf(
            "usage_day_",
            "blocked_ms_",
            "blocked_count_",
            "blocked_attempt_"
        )

        val out = HashSet<String>()

        fun readLong(vAny: Any?): Long {
            return when (vAny) {
                is Long -> vAny
                is Int -> vAny.toLong()
                is Float -> vAny.toLong()
                is Double -> vAny.toLong()
                is Number -> vAny.toLong()
                is String -> vAny.toLongOrNull() ?: 0L
                else -> 0L
            }
        }

        for ((k, vAny) in sp.all) {
            val prefix = prefixes.firstOrNull { k.startsWith(it) } ?: continue
            val used = readLong(vAny)
            if (used <= 0L) continue
            val rest = k.removePrefix(prefix)
            val pkg = rest.substringAfter("_", missingDelimiterValue = "")
            if (pkg.isNotBlank()) out.add(pkg)
        }
        return out
    }

    private fun resolveLabel(pm: PackageManager, pkg: String): String {
        val cached = synchronized(labelCache) { labelCache.get(pkg) }
        if (cached != null) return cached

        val label = runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        }.getOrNull() ?: pkg

        synchronized(labelCache) { labelCache.put(pkg, label) }
        return label
    }

    private fun updateInsightsCard(
        profile: String,
        blockedAlwaysSet: Set<String>,
        pkgs: List<String>,
        pm: PackageManager,
        yearNow: Int,
        monthNow1: Int,
        weekDays: Int
    ) {
        // Only for Usage mode + Premium
        if (mode != Mode.USAGE || !PremiumManager.isPremium(this)) {
            b.cardInsights.visibility = View.GONE
            return
        }

        // managed apps only
        val managed = HashSet<String>().apply {
            addAll(blockedAlwaysSet)
            addAll(UsageLimitStore.getAllLimitedPackagesAnyProfile(this@StatsActivity))
            addAll(UsageLimitStore.getAllLimitedPackages(this@StatsActivity, profile))
        }

        val managedRows = lastRows.filter { managed.contains(it.packageName) }

        val usedTotal = managedRows.sumOf { it.usedMsToday.coerceAtLeast(0L) }
        val overLimitTotal = managedRows.sumOf { row ->
            val pkg = row.packageName
            val usedMs = row.usedMsToday.coerceAtLeast(0L)

            val allowedMs = when {
                blockedAlwaysSet.contains(pkg) -> 0L
                UsageLimitStore.getLimitMinutes(this, profile, pkg) > 0 ->
                    UsageLimitStore.getLimitMinutes(this, profile, pkg) * 60_000L
                else -> Long.MAX_VALUE
            }

            if (allowedMs == Long.MAX_VALUE) 0L else max(0L, usedMs - allowedMs)
        }

        val ratioLine = if (usedTotal <= 0L) {
            ""
        } else {
            val denom = usedTotal.coerceAtLeast(1L)
            val percent = ((overLimitTotal.toDouble()/denom.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            if (percent == 0) getString(R.string.stats_insights_ratio_none)
            else getString(R.string.stats_insights_ratio_fmt, percent)
        }

        val trendLine = when (range) {
            Range.WEEK -> {
                val current = managed.sumOf { UsageStore.getUsageMsForPastRange(this, it, 0, weekDays) }
                val prev = managed.sumOf { UsageStore.getUsageMsForPastRange(this, it, weekDays, weekDays) }
                formatTrend(current, prev)
            }
            Range.MONTH -> {
                val current = managed.sumOf { UsageStore.getUsageMsForMonth(this, it, yearNow, monthNow1) }
                val (py, pm1) = prevMonth(yearNow, monthNow1)
                val prev = managed.sumOf { UsageStore.getUsageMsForMonth(this, it, py, pm1) }
                formatTrend(current, prev)
            }
            Range.YEAR -> {
                val current = managed.sumOf { UsageStore.getUsageMsForYear(this, it, yearNow) }
                val prev = managed.sumOf { UsageStore.getUsageMsForYear(this, it, yearNow - 1) }
                formatTrend(current, prev)
            }
            else -> getString(R.string.stats_insights_trend_no_data)
        }

        val improvedLine = when (range) {
            Range.WEEK -> mostImprovedForWeek(managed.toList(), pm, weekDays)
            Range.MONTH -> mostImprovedForMonth(managed.toList(), pm, yearNow, monthNow1)
            Range.YEAR -> mostImprovedForYear(managed.toList(), pm, yearNow)
            else -> getString(R.string.stats_insights_improved_none)
        }

        b.cardInsights.visibility = View.VISIBLE
        b.tvInsightsTrend.text = trendLine
        b.tvInsightsRatio.text = ratioLine
        b.tvInsightsImproved.text = improvedLine
    }

    private fun formatTrend(current: Long, previous: Long): String {
        if (previous < 10 * 60_000L) {
            return if (current >= 10 * 60_000L) getString(R.string.stats_insights_trend_new)
            else getString(R.string.stats_insights_trend_no_data)
        }
        val delta = ((current - previous).toDouble()/previous.toDouble()) * 100.0
        val pct = abs(delta).toInt().coerceAtMost(999)
        val arrow = when {
            delta > 0.5 -> "↑"
            delta < -0.5 -> "↓"
            else -> "→"
        }
        return getString(R.string.stats_insights_trend_fmt, arrow, pct)
    }

    private fun prevMonth(year: Int, month1: Int): Pair<Int, Int> {
        return if (month1 <= 1) Pair(year - 1, 12) else Pair(year, month1 - 1)
    }

    private fun mostImprovedForWeek(pkgs: List<String>, pm: PackageManager, weekDays: Int): String {
        val best = findMostImproved(
            pkgs = pkgs,
            pm = pm,
            baselineMinMs = 20 * 60_000L,
            minDropMs = 5 * 60_000L,
            current = { pkg -> UsageStore.getUsageMsForPastRange(this, pkg, 0, weekDays) },
            previous = { pkg -> UsageStore.getUsageMsForPastRange(this, pkg, weekDays, weekDays) }
        )
        return best?.let {
            getString(R.string.stats_insights_improved_fmt, it.label, it.percentDrop) +
                " (-${prettyMinutesShort(it.msDrop)})"
        } ?: getString(R.string.stats_insights_improved_none)
    }

    private fun mostImprovedForMonth(pkgs: List<String>, pm: PackageManager, year: Int, month1: Int): String {
        val (py, pm1) = prevMonth(year, month1)
        val best = findMostImproved(
            pkgs = pkgs,
            pm = pm,
            baselineMinMs = 60 * 60_000L,
            minDropMs = 15 * 60_000L,
            current = { pkg -> UsageStore.getUsageMsForMonth(this, pkg, year, month1) },
            previous = { pkg -> UsageStore.getUsageMsForMonth(this, pkg, py, pm1) }
        )
        return best?.let {
            getString(R.string.stats_insights_improved_fmt, it.label, it.percentDrop) +
                " (-${prettyMinutesShort(it.msDrop)})"
        } ?: getString(R.string.stats_insights_improved_none)
    }

    private fun mostImprovedForYear(pkgs: List<String>, pm: PackageManager, year: Int): String {
        val best = findMostImproved(
            pkgs = pkgs,
            pm = pm,
            baselineMinMs = 120 * 60_000L,  // 2h baseline
            minDropMs = 30 * 60_000L,       // 30m drop
            current = { pkg -> UsageStore.getUsageMsForYear(this, pkg, year) },
            previous = { pkg -> UsageStore.getUsageMsForYear(this, pkg, year - 1) }
        )
        return best?.let {
            getString(R.string.stats_insights_improved_fmt, it.label, it.percentDrop) +
                " (-${prettyMinutesShort(it.msDrop)})"
        } ?: getString(R.string.stats_insights_improved_none)
    }

    private fun findMostImproved(
        pkgs: List<String>,
        pm: PackageManager,
        baselineMinMs: Long,
        minDropMs: Long,
        current: (String) -> Long,
        previous: (String) -> Long
    ): Improvement? {
        var best: Improvement? = null
        var bestDropPct = 0

        for (pkg in pkgs) {
            val prev = previous(pkg)
            if (prev < baselineMinMs) continue

            val cur = current(pkg)
            if (cur >= prev) continue

            val dropMs = prev - cur
            if (dropMs < minDropMs) continue

            val dropPct = ((dropMs.toDouble()/prev.toDouble()) * 100.0).toInt().coerceIn(1, 999)
            if (dropPct > bestDropPct) {
                bestDropPct = dropPct
                best = Improvement(
                    pkg = pkg,
                    label = resolveLabel(pm, pkg),
                    percentDrop = dropPct,
                    msDrop = dropMs
                )
            }
        }
        return best
    }
}