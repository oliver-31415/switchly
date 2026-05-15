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
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.SwitchlyAccessibilityService
import at.saltyy.switchly.data.prefs.AttemptLimitStore
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.DomainLimitStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SessionLimitStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.databinding.ActivityScreenTimeDashboardBinding
import at.saltyy.switchly.feature.premium.PremiumInfoActivity
import at.saltyy.switchly.feature.settings.AccessibilityDisclosure
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.PermissionUtils
import at.saltyy.switchly.util.SystemBarColorCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenTimeDashboardActivity : AppCompatActivity() {

    private enum class Range { TODAY, WEEK, MONTH, YEAR, OVERALL }
    private enum class Filter { ALL_APPS, BLOCKED_ONLY }
    private enum class Sort { USED_TIME, NAME_AZ, NAME_ZA }
    private enum class SortDir { DESC, ASC }

    private var filter: Filter = Filter.ALL_APPS
    private var sort: Sort = Sort.USED_TIME
    private var sortDir: SortDir = SortDir.DESC
    private var lastApps: List<AppUsage> = emptyList()
    private var lastWebsites: List<AppUsage> = emptyList()
    private var currentBlockedSet: Set<String> = emptySet()
    private var currentWebsiteRuleSet: Set<String> = emptySet()
    private var isWebMode: Boolean = false
    private var fallbackDeviceRange: Range? = null
    private var fallbackPromptShownForRange: Range? = null

    private lateinit var b: ActivityScreenTimeDashboardBinding
    private lateinit var adapter: AppUsageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        b = ActivityScreenTimeDashboardBinding.inflate(layoutInflater)
        setContentView(b.root)

        EdgeToEdgeUtils.setupClassic(
            activity = this,
            toolbar = b.toolbar
        )

        setSupportActionBar(b.toolbar)
        // Ensure the nav icon is treated as an "up" affordance and always works.
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Keep system bars dark for readability (matches Stats/Schedules).
        SystemBarColorCompat.setStatusBarColor(window, ContextCompat.getColor(this, android.R.color.black))
        SystemBarColorCompat.setNavigationBarColor(window, ContextCompat.getColor(this, android.R.color.black))
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false

        adapter = AppUsageAdapter(
            onClick = { item ->
                if (!PremiumManager.isPremium(this)) {
                    showPremiumStatsDetailsGate()
                } else {
                    val selectedRange = when (b.chipGroupRange.checkedChipId) {
                        b.chipToday.id -> ScreenTimeDetailActivity.RANGE_TODAY
                        b.chipMonth.id -> ScreenTimeDetailActivity.RANGE_MONTH
                        b.chipYear.id -> ScreenTimeDetailActivity.RANGE_YEAR
                        b.chipOverall.id -> ScreenTimeDetailActivity.RANGE_OVERALL
                        else -> ScreenTimeDetailActivity.RANGE_WEEK
                    }

                    if (isWebMode) {
                        startActivity(
                            Intent(this, WebsiteDetailActivity::class.java)
                                .putExtra(WebsiteDetailActivity.EXTRA_DOMAIN, item.packageName)
                                .putExtra(WebsiteDetailActivity.EXTRA_LABEL, item.label)
                                .putExtra(WebsiteDetailActivity.EXTRA_INITIAL_RANGE, when (b.chipGroupRange.checkedChipId) {
                                    b.chipToday.id -> WebsiteDetailActivity.RANGE_TODAY
                                    b.chipMonth.id -> WebsiteDetailActivity.RANGE_MONTH
                                    b.chipYear.id -> WebsiteDetailActivity.RANGE_YEAR
                                    b.chipOverall.id -> WebsiteDetailActivity.RANGE_OVERALL
                                    else -> WebsiteDetailActivity.RANGE_WEEK
                                })
                        )
                    } else {
                        startActivity(
                            Intent(this, ScreenTimeDetailActivity::class.java)
                                .putExtra(ScreenTimeDetailActivity.EXTRA_PKG, item.packageName)
                                .putExtra(ScreenTimeDetailActivity.EXTRA_LABEL, item.label)
                                .putExtra(ScreenTimeDetailActivity.EXTRA_INITIAL_RANGE, selectedRange)
                        )
                    }
                }
            },
            onEditLimits = { item ->
                if (isWebMode) {
                    QuickLimitDialogs.showForWebsite(
                        activity = this,
                        domain = item.packageName,
                        label = item.label
                    ) { refresh() }
                } else {
                    QuickLimitDialogs.showForApp(
                        activity = this,
                        pkg = item.packageName,
                        label = item.label
                    ) { refresh() }
                }
            }
            ,
            limitBadgeProvider = limitBadgeProvider@{ item ->
                if (isWebMode) {
                    val d = DomainBlockStore.normalize(item.packageName) ?: item.packageName
                    val always = DomainBlockStore.getDomains(this).contains(d)
                    val min = DomainLimitStore.getLimitMinutes(this, d)
                    when {
                        always -> getString(R.string.rule_block_always)
                        min > 0 -> getString(R.string.daily_limit_value_format, min)
                        else -> null
                    }
                } else {
                    val profile = ProfileStore.getCurrent(this)
                    if (profile.isNullOrBlank()) return@limitBadgeProvider null
                    val t = UsageLimitStore.getLimitMinutes(this, profile, item.packageName)
                    val a = AttemptLimitStore.getLimitAttempts(this, profile, item.packageName)
                    val parts = mutableListOf<String>()
                    if (t > 0) parts += getString(R.string.daily_limit_value_format, t)
                    if (a > 0) parts += resources.getQuantityString(R.plurals.daily_attempt_limit_value_format, a, a)
                    if (parts.isEmpty()) null else parts.joinToString(" • ")
                }
            }
        )

        adapter.setDetailsCtaEnabled(true)
        b.rowTapHint.isVisible = true
        b.rowTapHint.text = getString(R.string.usage_row_tap_hint_app)
        b.toolbar.title = getString(R.string.statistics_usage_title)

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        // default selections
        syncRangeChipUi(b.chipToday.id)
        b.toggleType.check(b.btnApps.id)
        updateRangeVisibilityForCurrentMode()

        b.chipToday.setOnClickListener { setRangeChip(b.chipToday.id) }
        b.chipWeek.setOnClickListener { setRangeChip(b.chipWeek.id) }
        b.chipMonth.setOnClickListener { setRangeChip(b.chipMonth.id) }
        b.chipYear.setOnClickListener { setRangeChip(b.chipYear.id) }
        b.chipOverall.setOnClickListener { setRangeChip(b.chipOverall.id) }
        b.toggleType.addOnButtonCheckedListener { _, _, _ ->
            fallbackDeviceRange = null
            fallbackPromptShownForRange = null
            updateRangeVisibilityForCurrentMode()
            refresh()
        }

        b.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        applyAccentUi()

        b.fabSortFilter.setOnClickListener { v ->
            showSortFilterMenu(v)
        }

        refresh()

        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) { UsageHistoryBackfill.maybeRun(this@ScreenTimeDashboardActivity) }
            if (changed) refresh()
        }
    }

    private fun showPremiumStatsDetailsGate() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.usage_details_premium_title)
            .setMessage(R.string.usage_details_premium_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.usage_details_premium_action) { _, _ ->
                startActivity(Intent(this, PremiumInfoActivity::class.java))
            }
            .showAccented()
    }

    override fun onResume() {
        super.onResume()
        applyAccentUi()
        syncRangeChipUi(b.chipGroupRange.checkedChipId.takeIf { it != View.NO_ID } ?: b.chipToday.id)
        updateRangeVisibilityForCurrentMode()
        refresh()
    }

    private fun applyAccentUi() {
        val accent = AccentColor.getAccentColorInt(this)
        val accentTint = ColorStateList.valueOf(accent)

        b.toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        // Keep the back arrow readable on the accent toolbar.
        val bg = AccentColor.getToolbarColor(this)
        val navTint = if (MaterialColors.isColorLight(bg)) Color.BLACK else Color.WHITE
        b.toolbar.navigationIcon?.mutate()?.setTint(navTint)
        // Keep labels neutral (text-colored). Accent stays on toggles + progress bars.
        b.btnOpenSettings.backgroundTintList = accentTint

        // Only style the Apps/Web toggle buttons here.
        listOf(b.btnApps, b.btnWeb).forEach { btn ->
            btn.strokeColor = accentTint
            btn.setTextColor(accent)
            btn.iconTint = accentTint
            btn.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x35))
        }
    }

    private fun updateRangeVisibilityForCurrentMode() {
        val isWeb = b.toggleType.checkedButtonId == b.btnWeb.id
        b.chipYear.visibility = View.VISIBLE
        b.chipOverall.visibility = View.VISIBLE
        if (b.chipGroupRange.checkedChipId == View.NO_ID) {
            syncRangeChipUi(b.chipToday.id)
        }
    }

    private fun setRangeChip(chipId: Int) {
        fallbackDeviceRange = null
        fallbackPromptShownForRange = null
        syncRangeChipUi(chipId)
        refresh()
    }

    private fun syncRangeChipUi(activeChipId: Int) {
        val activeBg = AccentColor.getAccentColorInt(this)
        val activeText = if (MaterialColors.isColorLight(activeBg)) Color.BLACK else Color.WHITE
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

    private fun refresh() {
        val range = when (b.chipGroupRange.checkedChipId) {
            b.chipToday.id -> Range.TODAY
            b.chipWeek.id -> Range.WEEK
            b.chipMonth.id -> Range.MONTH
            b.chipYear.id -> Range.YEAR
            b.chipOverall.id -> Range.OVERALL
            else -> Range.TODAY
        }

        val isWeb = b.toggleType.checkedButtonId == b.btnWeb.id
        isWebMode = isWeb
        if (isWeb) {
            fallbackDeviceRange = null
            fallbackPromptShownForRange = null
        }
        b.recycler.isVisible = true
        b.webPlaceholder.isVisible = false

        if (isWeb) {
            adapter.setDetailsCtaEnabled(true)
            b.rowTapHint.isVisible = true
            b.rowTapHint.text = getString(R.string.usage_row_tap_hint_website)
            b.toolbar.title = getString(R.string.statistics_usage_title)
            b.toolbar.subtitle = null
        val summary = when (range) {
                Range.TODAY -> WebUsageRepo.getTodaySummary(this)
                Range.WEEK -> WebUsageRepo.getLastNDaysSummary(this, 7)
                Range.MONTH -> WebUsageRepo.getThisMonthSummary(this)
                Range.YEAR -> WebUsageRepo.getThisYearSummary(this)
                Range.OVERALL -> WebUsageRepo.getOverallSummary(this)
            }

            val hasA11y = PermissionUtils.isAccessibilityServiceEnabled(this, SwitchlyAccessibilityService::class.java)
            b.totalTime.text = if (summary.totalTimeMs <= 0L) "—" else StatsFormat.prettyMsWithSeconds(summary.totalTimeMs)

            // Store latest web list + current rule set so sorting/filtering works.
            lastWebsites = summary.topApps
            val blocked = DomainBlockStore.getDomains(this).map { it.trim() }.filter { it.isNotBlank() }
            val limited = DomainLimitStore.getDomainsWithLimit(this).map { it.trim() }.filter { it.isNotBlank() }
            currentWebsiteRuleSet = (blocked + limited).toSet()

            applyAndShowCurrent()

            // Empty state handling
            val visibleEmpty = adapter.itemCount == 0
            b.webPlaceholder.isVisible = visibleEmpty
            if (visibleEmpty) {
                b.webPlaceholder.text = when {
                    !hasA11y -> getString(R.string.usage_websites_no_accessibility)
                    filter == Filter.BLOCKED_ONLY && currentWebsiteRuleSet.isEmpty() -> getString(R.string.usage_websites_no_blocked)
                    else -> getString(R.string.usage_websites_no_data)
                }
            }

            b.permHint.isVisible = !hasA11y
            if (!hasA11y) {
                b.permHint.text = getString(R.string.usage_websites_no_accessibility)
            }
            b.btnOpenSettings.isVisible = !hasA11y
            b.btnOpenSettings.text = getString(R.string.onb_open)
            b.btnOpenSettings.setOnClickListener {
                AccessibilityDisclosure.openSettingsWithDisclosure(this)
            }
            return
        }

        adapter.setDetailsCtaEnabled(true)
        b.toolbar.title = getString(R.string.statistics_usage_title)
        // Make it obvious that limits (time/attempts) are profile-bound.
        b.toolbar.subtitle = ProfileStore.getCurrent(this)?.let { getString(R.string.profile_active_fmt, it) }
        b.rowTapHint.text = getString(R.string.usage_row_tap_hint_app)

        val hasA11y = PermissionUtils.isAccessibilityServiceEnabled(this, SwitchlyAccessibilityService::class.java)
        val summary = when {
            range == Range.TODAY -> AppUsageRepo.getTodaySummary(this)
            fallbackDeviceRange == range -> getDeviceFallbackSummary(range)
            else -> when (range) {
                Range.TODAY -> AppUsageRepo.getTodaySummary(this)
                Range.WEEK -> AppUsageRepo.getLastNDaysSummary(this, 7)
                Range.MONTH -> AppUsageRepo.getThisMonthSummary(this)
                Range.YEAR -> AppUsageRepo.getThisYearSummary(this)
                Range.OVERALL -> AppUsageRepo.getOverallSummary(this)
            }
        }

        b.totalTime.text = if (summary.totalTimeMs <= 0L) "—" else StatsFormat.prettyMsWithSeconds(summary.totalTimeMs)

        // Keep blocked-app filter in sync with current profile.
        val profile = ProfileStore.getCurrent(this)
        currentBlockedSet = if (profile.isNullOrBlank()) {
            emptySet()
        } else {
            buildSet {
                addAll(ProfileStore.getBlockedForProfile(this@ScreenTimeDashboardActivity, profile))
                addAll(UsageLimitStore.getAllLimitedPackages(this@ScreenTimeDashboardActivity, profile))
                addAll(SessionLimitStore.getAllLimitedPackages(this@ScreenTimeDashboardActivity, profile))
                addAll(AttemptLimitStore.getAllLimitedPackages(this@ScreenTimeDashboardActivity, profile))
            }
        }

        lastApps = summary.topApps
        applyAndShowCurrent()

        if (range != Range.TODAY && fallbackDeviceRange != range && summary.topApps.isEmpty() && hasA11y) {
            maybeShowDeviceFallbackDialog(range)
        }

        val visibleEmpty = adapter.itemCount == 0
        b.webPlaceholder.isVisible = visibleEmpty
        if (visibleEmpty) {
            b.webPlaceholder.text = when {
                !hasA11y -> getString(R.string.usage_apps_no_accessibility)
                filter == Filter.BLOCKED_ONLY && currentBlockedSet.isEmpty() -> getString(R.string.usage_apps_no_blocked)
                else -> getString(R.string.usage_apps_no_data)
            }
        }

        b.permHint.isVisible = !hasA11y && visibleEmpty
        if (!hasA11y && visibleEmpty) {
            b.permHint.text = getString(R.string.usage_apps_no_accessibility)
        }
        b.btnOpenSettings.isVisible = !hasA11y && visibleEmpty
        b.btnOpenSettings.text = getString(R.string.onb_open)
        b.btnOpenSettings.setOnClickListener {
            AccessibilityDisclosure.openSettingsWithDisclosure(this)
        }
        b.rowTapHint.isVisible = !visibleEmpty
    }

    private fun getDeviceFallbackSummary(range: Range): UsageSummary {
        return when (range) {
            Range.TODAY -> AppUsageRepo.getTodaySummary(this)
            Range.WEEK -> AppUsageRepo.getDeviceSummary(this, 7)
            Range.MONTH -> AppUsageRepo.getDeviceSummary(this, 30)
            Range.YEAR -> AppUsageRepo.getDeviceSummary(this, 365)
            Range.OVERALL -> AppUsageRepo.getDeviceSummary(this, -1)
        }
    }

    private fun maybeShowDeviceFallbackDialog(range: Range) {
        if (fallbackPromptShownForRange == range) return
        fallbackPromptShownForRange = range
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.usage_switchly_fallback_title)
            .setMessage(R.string.usage_switchly_fallback_message)
            .setPositiveButton(R.string.usage_switchly_fallback_positive) { _, _ ->
                fallbackDeviceRange = range
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
    }

    private fun showSortFilterMenu(anchor: View) {
        // Use the same clean grouped dialog as the other Statistics screens.
        val v = layoutInflater.inflate(R.layout.dialog_sort_filter, null)
        val rgFilter = v.findViewById<RadioGroup>(R.id.rgFilter)
        val rgSort = v.findViewById<RadioGroup>(R.id.rgSort)

        // Adjust filter labels based on current type (Apps vs Websites).
        val isWeb = b.toggleType.checkedButtonId == b.btnWeb.id
        v.findViewById<RadioButton>(R.id.rbFilterAll).text = getString(
            if (isWeb) R.string.stats_filter_all_websites else R.string.stats_filter_all_apps
        )
        v.findViewById<RadioButton>(R.id.rbFilterBlocked).text = getString(
            if (isWeb) R.string.stats_filter_blocked_only_websites else R.string.stats_filter_blocked_only
        )

        // Primary sort labels
        v.findViewById<RadioButton>(R.id.rbSortPrimaryDesc).text = getString(R.string.stats_sort_used_time_desc)
        v.findViewById<RadioButton>(R.id.rbSortPrimaryAsc).text = getString(R.string.stats_sort_used_time_asc)

        // Initial selections
        when (filter) {
            Filter.ALL_APPS -> rgFilter.check(R.id.rbFilterAll)
            Filter.BLOCKED_ONLY -> rgFilter.check(R.id.rbFilterBlocked)
        }

        when (sort) {
            Sort.NAME_AZ -> rgSort.check(R.id.rbSortAz)
            Sort.NAME_ZA -> rgSort.check(R.id.rbSortZa)
            Sort.USED_TIME -> rgSort.check(if (sortDir == SortDir.ASC) R.id.rbSortPrimaryAsc else R.id.rbSortPrimaryDesc)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.stats_sort_filter_title))
            .setView(v)
            .setPositiveButton(getString(R.string.stats_apply)) { _, _ ->
                filter = when (rgFilter.checkedRadioButtonId) {
                    R.id.rbFilterBlocked -> Filter.BLOCKED_ONLY
                    else -> Filter.ALL_APPS
                }

                when (rgSort.checkedRadioButtonId) {
                    R.id.rbSortAz -> sort = Sort.NAME_AZ
                    R.id.rbSortZa -> sort = Sort.NAME_ZA
                    R.id.rbSortPrimaryAsc -> { sort = Sort.USED_TIME; sortDir = SortDir.ASC }
                    else -> { sort = Sort.USED_TIME; sortDir = SortDir.DESC }
                }

                applyAndShowCurrent()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
    }

    private fun applyAndShowCurrent() {
        if (b.toggleType.checkedButtonId == b.btnWeb.id) {
            applyAndShowWebsites()
        } else {
            applyAndShowApps()
        }
    }

    private fun applyAndShowApps() {

        // When the user selects "Only blocked apps" they typically expect to see *all* configured blocked apps, even if usage is 0 today.
        // So we merge the blocked set into the list.
        val base = when (filter) {
            Filter.ALL_APPS -> lastApps
            Filter.BLOCKED_ONLY -> {
                val byPkg = lastApps.associateBy { it.packageName }.toMutableMap()
                val pm = packageManager

                for (pkg in currentBlockedSet) {
                    if (byPkg.containsKey(pkg)) continue
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrElse { pkg }
                    val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                    byPkg[pkg] = AppUsage(
                        packageName = pkg,
                        label = label,
                        icon = icon,
                        timeMs = 0L,
                        percent = 0f
                    )
                }

                byPkg.values.filter { it.packageName in currentBlockedSet }
            }
        }

        // Recompute percents for the currently visible list (so bars/percentages stay consistent after filtering/sorting).
        // IMPORTANT: `percent` is stored as a fraction (0.0..1.0). UI formatting multiplies by 100.
        val totalMs = base.sumOf { it.timeMs }.coerceAtLeast(1L)
        val filtered = base.map { it.copy(percent = (it.timeMs.toFloat()/totalMs.toFloat()).coerceIn(0f, 1f)) }

        val sorted = when (sort) {
            Sort.NAME_AZ -> filtered.sortedBy { it.label.lowercase() }
            Sort.NAME_ZA -> filtered.sortedByDescending { it.label.lowercase() }
            Sort.USED_TIME -> {
                val cmp = when (sortDir) {
                    SortDir.DESC -> compareByDescending<AppUsage> { it.timeMs }
                    SortDir.ASC -> compareBy<AppUsage> { it.timeMs }
                }
                filtered.sortedWith(cmp.thenBy { it.label.lowercase() })
            }
        }

        adapter.submit(sorted)
    }

    private fun applyAndShowWebsites() {
        // Similar UX to apps: "Only blocked" should show all configured rules, even if usage is 0.
        val base = when (filter) {
            Filter.ALL_APPS -> lastWebsites
            Filter.BLOCKED_ONLY -> {
                val byDomain = lastWebsites.associateBy { it.packageName }.toMutableMap()
                val icon = ContextCompat.getDrawable(this, R.drawable.language_24)
                for (domain in currentWebsiteRuleSet) {
                    if (byDomain.containsKey(domain)) continue
                    byDomain[domain] = AppUsage(
                        packageName = domain,
                        label = domain,
                        icon = icon,
                        timeMs = 0L,
                        percent = 0f
                    )
                }
                byDomain.values.filter { it.packageName in currentWebsiteRuleSet }
            }
        }

        val totalMs = base.sumOf { it.timeMs }.coerceAtLeast(1L)
        val filtered = base.map { it.copy(percent = (it.timeMs.toFloat()/totalMs.toFloat()).coerceIn(0f, 1f)) }

        val sorted = when (sort) {
            Sort.NAME_AZ -> filtered.sortedBy { it.label.lowercase() }
            Sort.NAME_ZA -> filtered.sortedByDescending { it.label.lowercase() }
            Sort.USED_TIME -> {
                val cmp = when (sortDir) {
                    SortDir.DESC -> compareByDescending<AppUsage> { it.timeMs }
                    SortDir.ASC -> compareBy<AppUsage> { it.timeMs }
                }
                filtered.sortedWith(cmp.thenBy { it.label.lowercase() })
            }
        }

        adapter.submit(sorted)
    }

    companion object {
        fun intent(ctx: android.content.Context) = Intent(ctx, ScreenTimeDashboardActivity::class.java)
    }
}
