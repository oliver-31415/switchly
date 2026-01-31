package at.saltyy.switchly.feature.stats

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.util.LruCache
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.BlockAttemptStore
import at.saltyy.switchly.data.prefs.BlockCountStore
import at.saltyy.switchly.data.prefs.BlockedTimeStore
import at.saltyy.switchly.data.prefs.NfcScanCountStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ScheduleExecutionCountStore
import at.saltyy.switchly.data.prefs.SwitchlyRuntimeStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.databinding.ActivityStatsBinding
import at.saltyy.switchly.feature.schedule.SchedulesActivity
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max

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

    // Sorting: only A-Z and Used Time
    private enum class Sort { NAME_AZ, USED_TIME }

    private var range: Range = Range.TODAY
    private var sort: Sort = Sort.USED_TIME
    private var lastRows: List<StatsRow> = emptyList()

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
        val nfcUsed: Int,
        val schedulesExecuted: Int,
        val blockedAppsNow: Int
    ) : ComputedBase

    /**
     * "Week" in the UI means *current calendar week to date* (Mon..today), not "last 7 days".
     *
     * Calendar.DAY_OF_WEEK: 1=Sunday, 2=Monday, ... 7=Saturday
     */
    private fun weekToDateDays(calNow: Calendar = Calendar.getInstance()): Int {
        val dow = calNow.get(Calendar.DAY_OF_WEEK)
        // Convert to Monday=0..Sunday=6
        val offsetFromMonday = (dow + 5) % 7
        return (offsetFromMonday + 1).coerceIn(1, 7)
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
            "runtime" -> Mode.RUNTIME
            "blocking" -> Mode.BLOCKING
            "other" -> Mode.OTHER
            else -> Mode.USAGE
        }

        EdgeToEdgeUtils.setupClassic(
            activity = this,
            toolbar = b.toolbar,
            bottomNav = b.bottomNav
        )

        // Match Schedules look: keep BottomNav slightly above the gesture area on all devices
        EdgeToEdgeUtils.applyBottomNavGestureInset(b.bottomNav)

        // Keep status bar neutral (no accent bleed into system bar)
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
        // Match schedules: keep navigation bar dark so the system gesture/nav area reads as spacing
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.black)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false

        setSupportActionBar(b.toolbar)
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

        setupBottomNav()

        b.recycler.layoutManager = LinearLayoutManager(this)
        usageAdapter = StatsAdapter()
        blockAdapter = BlockStatsAdapter()
        runtimeAdapter = RuntimeBlockedAdapter()

        setupRangeChips()
        applyRangePremiumLock()

        // sort chip (usage-only)
        b.chipSort.setOnClickListener { cycleSort(withAnim = true) }

        load()
    }

    override fun onResume() {
        super.onResume()
        b.toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        applyRangePremiumLock()
        load()
    }

    private fun setupBottomNav() {
        val bottomNav: BottomNavigationView = b.bottomNav
        bottomNav.selectedItemId = R.id.nav_stats

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_schedules -> {
                    startActivity(Intent(this, SchedulesActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_stats -> {
                    startActivity(Intent(this, StatisticsHubActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRangeChips() {
        b.chipToday.isChecked = true

        fun setRange(target: Range) {
            range = target
            load()
        }

        b.chipToday.setOnClickListener { setRange(Range.TODAY) }
        b.chipWeek.setOnClickListener { setRange(Range.WEEK) }
        b.chipMonth.setOnClickListener { setRange(Range.MONTH) }
        b.chipYear.setOnClickListener { setRange(Range.YEAR) }
        b.chipOverall.setOnClickListener { setRange(Range.OVERALL) }
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
            b.chipToday.isChecked = true
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

        // Only 2 sorts now (no % / THIRD anymore)
        sort = when (sort) {
            Sort.USED_TIME -> Sort.NAME_AZ
            Sort.NAME_AZ -> Sort.USED_TIME
        }
        applyAndShow()
    }

    private fun applyAndShow() {
        val sorted = when (sort) {
            Sort.NAME_AZ -> lastRows.sortedBy { it.appName.lowercase() }
            Sort.USED_TIME -> lastRows.sortedWith(
                compareByDescending<StatsRow> { it.usedMsToday }.thenBy { it.appName.lowercase() }
            )
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

        val sortLabel = when (sort) {
            Sort.NAME_AZ -> getString(R.string.stats_sort_az)
            Sort.USED_TIME -> getString(R.string.stats_sort_used_time)
        }
        b.chipSort.text = getString(R.string.stats_sort_hint_fmt, sortLabel)
    }

    private fun load() {
        val profile = ProfileStore.getCurrent(this)

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

                    b.emptyText.isVisible = computed.rows.isEmpty()
                    if (b.emptyText.isVisible) {
                        b.emptyText.gravity = Gravity.CENTER
                        b.emptyText.text = getString(R.string.stats_empty)
                    }

                    blockAdapter.submit(computed.rows)
                }

                is RuntimeComputed -> {
                    b.tvTodayTitle.text = getString(R.string.stats_mode_runtime)
                    b.tvTodaySubtitle.visibility = View.VISIBLE
                    b.tvTodaySubtitle.text = getString(R.string.stats_runtime_subtitle)

                    runtimeAdapter.submit(computed.rows)

                    b.emptyText.isVisible = computed.rows.isEmpty() && computed.runtimeMs <= 0L
                    if (b.emptyText.isVisible) {
                        b.emptyText.gravity = Gravity.CENTER
                        b.emptyText.text = getString(R.string.stats_empty)
                    }

                    // Info card shows runtime summary
                    b.cardInfo.visibility = View.VISIBLE
                    b.tvInfoLabel.text = rangeLabel()
                    b.tvInfoPrimary.text = prettyMs(computed.runtimeMs)
                    b.tvInfoSecondary.text = getString(
                        R.string.stats_runtime_summary_fmt,
                        prettyMs(computed.runtimeMs),
                        prettyMs(computed.totalBlockedMs)
                    )

                    // Move "Top blocked apps" OUT of the box:
                    b.tvInfoDetails.visibility = View.GONE
                    b.tvListHeader.visibility = View.VISIBLE
                    b.tvListHeader.text = getString(R.string.stats_runtime_top_blocked)
                }

                is OtherComputed -> {
                    b.tvTodayTitle.text = getString(R.string.stats_mode_other)
                    b.tvTodaySubtitle.visibility = View.VISIBLE
                    b.tvTodaySubtitle.text = getString(R.string.stats_other_subtitle)

                    val totalEvents = computed.emergencyUsed + computed.nfcUsed + computed.schedulesExecuted

                    b.cardInfo.visibility = View.VISIBLE
                    b.tvInfoLabel.text = rangeLabel()
                    b.tvInfoPrimary.text = NumberFormat.getInstance().format(totalEvents)
                    b.tvInfoSecondary.text = getString(R.string.stats_other_primary_caption)

                    b.llInfoRows.visibility = View.VISIBLE
                    b.tvInfoNote.visibility = View.VISIBLE
                    b.tvInfoNote.text = getString(R.string.stats_other_not_in_total)

                    b.tvRowEmergency.text = getString(R.string.stats_other_line_emergency, computed.emergencyUsed)
                    b.tvRowNfc.text = getString(R.string.stats_other_line_nfc_scans, computed.nfcUsed)
                    b.tvRowSchedules.text = getString(R.string.stats_other_line_schedules_executed, computed.schedulesExecuted)
                    b.tvRowBlockedApps.text = getString(R.string.stats_other_line_blocked_apps, computed.blockedAppsNow)

                    b.emptyText.isVisible = (totalEvents == 0 && computed.blockedAppsNow == 0)
                    if (b.emptyText.isVisible) {
                        b.emptyText.gravity = Gravity.CENTER
                        b.emptyText.text = getString(R.string.stats_empty)
                    }
                }
            }
        }
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
        }.toList().sorted()

        val everLimitedSet = UsageLimitStore.getAllEverLimitedPackages(this).toHashSet()
        val blockedAlwaysSet = ProfileStore.getBlockedForProfile(this@StatsActivity, profile).toHashSet()

        fun usageMsFor(pkg: String): Long = when (range) {
            Range.TODAY -> UsageStore.getUsageMsToday(this, pkg)
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
                    .sortedWith(compareByDescending<BlockStatsRow> { it.blockedMs }.thenBy { it.appName.lowercase() })

                BlockingComputed(
                    pkgs = pkgs,
                    weekDays = weekDays,
                    yearNow = yearNow,
                    monthNow1 = monthNow1,
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
                    val blockedMs = blockedMsFor(pkg)
                    val blocks = blockedCountFor(pkg)
                    val attempts = attemptsFor(pkg)

                    if (blockedMs <= 0L && blocks <= 0 && attempts <= 0) return@mapNotNull null

                    RuntimeBlockedRow(
                        packageName = pkg,
                        appName = resolveLabel(pm, pkg),
                        blockedMs = blockedMs,
                        blockedCount = blocks,
                        attemptCount = attempts,
                        scoreMs = blockedMs
                    )
                }.sortedWith(
                    compareByDescending<RuntimeBlockedRow> { it.blockedMs }
                        .thenByDescending { it.attemptCount }
                        .thenByDescending { it.blockedCount }
                        .thenBy { it.appName.lowercase() }
                )

                val totalBlockedMs = rows.sumOf { it.blockedMs }

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

                val nfcUsed = when (range) {
                    Range.TODAY -> NfcScanCountStore.getToday(this)
                    Range.WEEK -> NfcScanCountStore.getForLastNDays(this, weekDays)
                    Range.MONTH -> NfcScanCountStore.getForMonth(this, yearNow, monthNow1)
                    Range.YEAR -> NfcScanCountStore.getForYear(this, yearNow)
                    Range.OVERALL -> NfcScanCountStore.getOverall(this)
                }

                val schedulesExecuted = when (range) {
                    Range.TODAY -> ScheduleExecutionCountStore.getToday(this)
                    Range.WEEK -> ScheduleExecutionCountStore.getForLastNDays(this, weekDays)
                    Range.MONTH -> ScheduleExecutionCountStore.getForMonth(this, yearNow, monthNow1)
                    Range.YEAR -> ScheduleExecutionCountStore.getForYear(this, yearNow)
                    Range.OVERALL -> ScheduleExecutionCountStore.getOverall(this)
                }

                val blockedAppsNow = pkgs.count { pkg ->
                    blockedMsFor(pkg) > 0L || blockedCountFor(pkg) > 0 || attemptsFor(pkg) > 0
                }

                OtherComputed(
                    pkgs = pkgs,
                    weekDays = weekDays,
                    yearNow = yearNow,
                    monthNow1 = monthNow1,
                    emergencyUsed = emergencyUsed,
                    nfcUsed = nfcUsed,
                    schedulesExecuted = schedulesExecuted,
                    blockedAppsNow = blockedAppsNow
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
            val percent = ((overLimitTotal.toDouble() / denom.toDouble()) * 100.0).toInt().coerceIn(0, 100)
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
        val totalSec = (ms / 1000L).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm".format(m)
    }

    private fun prettyMinutesShort(ms: Long): String {
        val m = (ms / 60_000L).toInt().coerceAtLeast(0)
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
            val percent = ((overLimitTotal.toDouble() / denom.toDouble()) * 100.0).toInt().coerceIn(0, 100)
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
        val delta = ((current - previous).toDouble() / previous.toDouble()) * 100.0
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

            val dropPct = ((dropMs.toDouble() / prev.toDouble()) * 100.0).toInt().coerceIn(1, 999)
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
