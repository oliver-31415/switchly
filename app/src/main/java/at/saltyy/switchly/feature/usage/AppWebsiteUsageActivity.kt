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
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import at.saltyy.switchly.data.prefs.BlockAttemptStore
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.DomainLimitStore
import at.saltyy.switchly.data.prefs.OpenCountStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.WebsiteRuleModeStore
import at.saltyy.switchly.data.prefs.SessionLimitStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageLimitResetStore
import at.saltyy.switchly.databinding.ActivityStatisticsAppWebsiteUsageBinding
import at.saltyy.switchly.feature.settings.AccessibilityDisclosure
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.SegmentedToggleUi
import at.saltyy.switchly.ui.SwitchlyDropdownAdapter
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.PermissionUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class AppWebsiteUsageActivity : AppCompatActivity() {
    private fun readableOnColor(color: Int): Int {
        val isLight = ColorUtils.calculateLuminance(color) > 0.45

        return if (isLight) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }

    private fun showStatisticsInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.statistics_usage_info_title)
            .setMessage(R.string.statistics_usage_info_body)
            .setPositiveButton(android.R.string.ok, null)
            .showAccented()
    }

    private enum class Range { TODAY, WEEK, MONTH, YEAR, CUSTOM }
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
    private var currentMetricsByPackage: Map<String, AppUsageMetrics> = emptyMap()
    private var isWebMode: Boolean = false
    private var currentRange: Range = Range.TODAY
    private var currentProfileName: String? = null
    private var fallbackDeviceRange: Range? = null
    private var fallbackDeviceSummary: UsageSummary? = null
    private var fallbackPromptShownForRange: Range? = null
    private var refreshJob: Job? = null
    private var refreshVersion: Int = 0
    private var customRangeStartMillis: Long? = null
    private var customRangeEndMillis: Long? = null
    private var customRangePickerShowing = false

    private lateinit var b: ActivityStatisticsAppWebsiteUsageBinding
    private lateinit var adapter: AppUsageAdapter

    private data class RefreshData(
        val summary: UsageSummary,
        val hasAccessibility: Boolean,
        val profile: String?,
        val blockedSet: Set<String> = emptySet(),
        val websiteRuleSet: Set<String> = emptySet(),
        val metricsByPackage: Map<String, AppUsageMetrics> = emptyMap()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        b = ActivityStatisticsAppWebsiteUsageBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.webPlaceholder.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ContextCompat.getColor(this@AppWebsiteUsageActivity, R.color.switchly_card_bg))
            setStroke(dp(1), ContextCompat.getColor(this@AppWebsiteUsageActivity, R.color.switchly_card_stroke))
            cornerRadius = dp(20).toFloat()
        }

        EdgeToEdgeUtils.setupClassic(
            activity = this,
            toolbar = b.toolbar
        )

        setSupportActionBar(b.toolbar)
        // Ensure the nav icon is treated as an "up" affordance and always works.
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        b.btnStatsInfo.setOnClickListener { showStatisticsInfo() }
        val accent = AccentColor.getAccentColorInt(this)
        b.fabSortFilter.imageTintList = ColorStateList.valueOf(readableOnColor(accent))

        // Keep system bars dark for readability (matches Stats/Schedules).
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false

        adapter = AppUsageAdapter(
            onClick = { item ->
                if (!StatsPremiumGate.isPremium(this)) {
                    StatsPremiumGate.show(this)
                } else {
                    val selectedRange = screenTimeDetailRange(currentRange)

                    if (isWebMode) {
                        val detailIntent = Intent(this, WebsiteUsageDetailActivity::class.java)
                            .putExtra(WebsiteUsageDetailActivity.EXTRA_DOMAIN, item.packageName)
                            .putExtra(WebsiteUsageDetailActivity.EXTRA_LABEL, item.label)
                            .putExtra(WebsiteUsageDetailActivity.EXTRA_INITIAL_RANGE, websiteDetailRange(currentRange))
                        if (currentRange == Range.CUSTOM) {
                            customRangeStartMillis?.let { detailIntent.putExtra(WebsiteUsageDetailActivity.EXTRA_INITIAL_START_MS, it) }
                            customRangeEndMillis?.let { detailIntent.putExtra(WebsiteUsageDetailActivity.EXTRA_INITIAL_END_MS, it) }
                        }
                        startActivity(
                            detailIntent
                        )
                    } else {
                        val detailIntent = Intent(this, AppUsageDetailActivity::class.java)
                            .putExtra(AppUsageDetailActivity.EXTRA_PKG, item.packageName)
                            .putExtra(AppUsageDetailActivity.EXTRA_LABEL, item.label)
                            .putExtra(AppUsageDetailActivity.EXTRA_INITIAL_RANGE, selectedRange)
                        if (currentRange == Range.CUSTOM) {
                            customRangeStartMillis?.let { detailIntent.putExtra(AppUsageDetailActivity.EXTRA_INITIAL_START_MS, it) }
                            customRangeEndMillis?.let { detailIntent.putExtra(AppUsageDetailActivity.EXTRA_INITIAL_END_MS, it) }
                        }
                        startActivity(
                            detailIntent
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
            },
            limitBadgeProvider = { item ->
                if (isWebMode) websiteLimitBadge(item) else appLimitBadge(item)
            },
            usageMetricsProvider = { item ->
                if (isWebMode) null else currentMetricsByPackage[item.packageName]
            }
        )

        adapter.setDetailsCtaEnabled(true)
        b.rowTapHint.isVisible = true
        b.rowTapHint.text = getString(R.string.usage_row_tap_hint_app)
        b.toolbar.title = getString(R.string.statistics_usage_title)
        b.statsPageSubtitle.isVisible = false

        configureRangeFilterButtons()
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        // default selections
        syncRangeChipUi(b.chipToday.id)
        b.toggleType.check(b.btnApps.id)
        syncTypeToggleUi()
        updateRangeVisibilityForCurrentMode()

        b.chipToday.setOnClickListener { setRangeChip(b.chipToday.id) }
        b.chipWeek.setOnClickListener { setRangeChip(b.chipWeek.id) }
        b.chipMonth.setOnClickListener { setRangeChip(b.chipMonth.id) }
        b.chipYear.setOnClickListener { setRangeChip(b.chipYear.id) }
        b.chipCustom.setOnClickListener { showCustomRangePicker() }
        b.btnClearCustomRange.setOnClickListener {
            customRangeStartMillis = null
            customRangeEndMillis = null
            setRangeChip(b.chipToday.id)
        }
        b.toggleType.addOnButtonCheckedListener { _, _, _ ->
            fallbackDeviceRange = null
            fallbackDeviceSummary = null
            fallbackPromptShownForRange = null
            updateRangeVisibilityForCurrentMode()
            syncTypeToggleUi()
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
            val changed = withContext(Dispatchers.IO) { UsageHistoryBackfill.maybeRun(this@AppWebsiteUsageActivity) }
            if (changed) refresh()
        }
    }

    private fun websiteLimitBadge(item: AppUsage): String? {
        val domain = DomainBlockStore.normalize(item.packageName) ?: item.packageName
        val alwaysBlocked = DomainBlockStore.getDomains(this).contains(domain)
        val allowMode = ProfileStore.getCurrent(this)
            ?.let { WebsiteRuleModeStore.isAllowMode(this, it) } == true
        val dailyLimitMinutes = DomainLimitStore.getLimitMinutes(this, domain)

        return when {
            alwaysBlocked -> getString(
                if (allowMode) R.string.rule_allowed_always else R.string.rule_block_always
            )
            dailyLimitMinutes > 0 -> getString(R.string.daily_limit_value_format, dailyLimitMinutes)
            else -> null
        }
    }

    private fun appLimitBadge(item: AppUsage): String? {
        val profile = ProfileStore.getCurrent(this)?.takeIf { it.isNotBlank() } ?: return null
        return appLimitBadgeParts(profile, item.packageName).takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }

    private fun buildAppUsageMetricsMap(
        packages: Set<String>,
        range: Range,
    ): Map<String, AppUsageMetrics> {
        if (packages.isEmpty()) {
            return emptyMap()
        }
        val (startMs, endMs) = rangeBounds(range) ?: return emptyMap()
        val opensByPackage = OpenCountStore.getMapForDateRangeAllProfiles(this, startMs, endMs)
        val blocksByPackage = BlockAttemptStore.getMapForDateRange(this, startMs, endMs)

        return packages.associateWith { packageName ->
            val opens = opensByPackage[packageName] ?: 0
            val blocks = blocksByPackage[packageName] ?: 0
            AppUsageMetrics(
                opensLabel = if (opens <= 0) {
                    getString(R.string.usage_opens_zero)
                } else {
                    resources.getQuantityString(R.plurals.opens_format, opens, opens)
                },
                attemptsLabel = if (blocks <= 0) {
                    getString(R.string.usage_attempts_zero)
                } else {
                    resources.getQuantityString(R.plurals.attempts_format, blocks, blocks)
                }
            )
        }
    }

    private fun rangeBounds(range: Range): Pair<Long, Long>? {
        val now = Calendar.getInstance()
        val endMs = now.timeInMillis
        val start = Calendar.getInstance().apply {
            timeInMillis = endMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        when (range) {
            Range.TODAY -> Unit
            Range.WEEK -> {
                val daysSinceWeekStart =
                    (7 + (start.get(Calendar.DAY_OF_WEEK) - start.firstDayOfWeek)) % 7
                start.add(Calendar.DAY_OF_YEAR, -daysSinceWeekStart)
            }
            Range.MONTH -> start.set(Calendar.DAY_OF_MONTH, 1)
            Range.YEAR -> {
                start.set(Calendar.MONTH, Calendar.JANUARY)
                start.set(Calendar.DAY_OF_MONTH, 1)
            }
            Range.CUSTOM -> {
                val customStart = customRangeStartMillis ?: return null
                val customEnd = customRangeEndMillis ?: return null
                return customStart to customEnd
            }
        }
        return start.timeInMillis to endMs
    }

    private fun appLimitBadgeParts(profile: String, packageName: String): List<String> {
        val timeLimitMinutes = UsageLimitStore.getLimitMinutes(this, profile, packageName)
        val attemptLimit = AttemptLimitStore.getLimitAttempts(this, profile, packageName)

        return buildList {
            if (timeLimitMinutes > 0) {
                add(formatTimeLimitBadge(profile, packageName, timeLimitMinutes))
            }

            if (attemptLimit > 0) {
                add(resources.getQuantityString(R.plurals.daily_attempt_limit_value_format, attemptLimit, attemptLimit))
            }
        }
    }

    private fun formatTimeLimitBadge(profile: String, packageName: String, minutes: Int): String {
        val resetMode = UsageLimitResetStore.getMode(this, profile, packageName)
        val formatRes = if (resetMode == UsageLimitResetStore.MODE_SESSION) {
            R.string.session_reset_limit_value_format
        } else {
            R.string.daily_limit_value_format
        }
        return getString(formatRes, minutes)
    }

    override fun onResume() {
        super.onResume()
        applyAccentUi()
        syncRangeChipUi(chipIdForRange(currentRange))
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
        b.btnStatsInfo.imageTintList = ColorStateList.valueOf(navTint)
        // Keep labels neutral (text-colored). Accent stays on toggles + progress bars.
        b.btnOpenSettings.backgroundTintList = accentTint
        b.totalTime.setTextColor(accent)
        b.cardUsageTotal.setCardBackgroundColor(ContextCompat.getColor(this, R.color.switchly_card_bg))
        b.cardUsageTotal.strokeColor = ContextCompat.getColor(this, R.color.switchly_card_stroke)
        b.cardUsageTotal.strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)

        syncTypeToggleUi()
    }

    private fun syncTypeToggleUi() {
        val activeId = b.toggleType.checkedButtonId.takeIf { it != View.NO_ID } ?: b.btnApps.id
        SegmentedToggleUi.apply(this, listOf(b.btnApps, b.btnWeb), activeId)
    }

    private fun updateRangeVisibilityForCurrentMode() {
        b.chipYear.visibility = View.VISIBLE
    }

    private fun setRangeChip(chipId: Int) {
        val requestedRange = rangeForChipId(chipId)
        if (!ensureRangeAllowed(requestedRange)) {
            syncRangeChipUi(chipIdForRange(currentRange))
            return
        }
        fallbackDeviceRange = null
        fallbackDeviceSummary = null
        fallbackPromptShownForRange = null
        currentRange = requestedRange
        syncRangeChipUi(chipId)
        refresh()
    }

    private fun ensureRangeAllowed(range: Range): Boolean {
        if (range == Range.TODAY || StatsPremiumGate.canUseExtendedStats(this)) {
            return true
        }
        StatsPremiumGate.show(this)
        return false
    }

    private fun showCustomRangePicker() {
        if (!ensureRangeAllowed(Range.CUSTOM)) {
            syncRangeChipUi(chipIdForRange(currentRange))
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
            fallbackDeviceRange = null
            fallbackDeviceSummary = null
            fallbackPromptShownForRange = null
            currentRange = Range.CUSTOM
            syncRangeChipUi(b.chipCustom.id)
            refresh()
        }
        picker.addOnDismissListener { customRangePickerShowing = false }
        runCatching { picker.show(supportFragmentManager, "usage_stats_custom_range") }
            .onSuccess { UsageDatePickerAccentTint.apply(this, picker) }
            .onFailure { customRangePickerShowing = false }
    }

    private fun configureRangeFilterButtons() {
        listOf(b.chipToday, b.chipWeek, b.chipMonth, b.chipYear).forEach { button ->
            configureRangeButton(button, custom = false)
        }
        configureRangeButton(b.chipCustom, custom = true)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun configureRangeButton(button: MaterialButton, custom: Boolean) {
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = dp(40)
        button.minimumHeight = dp(40)
        button.insetTop = 0
        button.insetBottom = 0
        button.cornerRadius = dp(4)
        button.shapeAppearanceModel = button.shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(dp(4).toFloat())
            .build()
        button.iconPadding = 0
        if (custom) {
            button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        button.gravity = android.view.Gravity.CENTER
        button.textSize = 14f
        button.setAllCaps(false)
        button.setPadding(if (custom) dp(8) else dp(4), 0, if (custom) dp(8) else dp(4), 0)
        button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (custom) dp(44) else 0
            height = dp(40)
            weight = if (custom) 0f else 1f
        }
    }

    private fun rangeForChipId(chipId: Int): Range {
        return when (chipId) {
            b.chipWeek.id -> Range.WEEK
            b.chipMonth.id -> Range.MONTH
            b.chipYear.id -> Range.YEAR
            b.chipCustom.id -> Range.CUSTOM
            else -> Range.TODAY
        }
    }

    private fun chipIdForRange(range: Range): Int {
        return when (range) {
            Range.TODAY -> b.chipToday.id
            Range.WEEK -> b.chipWeek.id
            Range.MONTH -> b.chipMonth.id
            Range.YEAR -> b.chipYear.id
            Range.CUSTOM -> b.chipCustom.id
        }
    }

    private fun screenTimeDetailRange(range: Range): String {
        return when (range) {
            Range.TODAY -> AppUsageDetailActivity.RANGE_TODAY
            Range.MONTH -> AppUsageDetailActivity.RANGE_MONTH
            Range.YEAR -> AppUsageDetailActivity.RANGE_YEAR
            Range.CUSTOM -> AppUsageDetailActivity.RANGE_CUSTOM
            else -> AppUsageDetailActivity.RANGE_WEEK
        }
    }

    private fun websiteDetailRange(range: Range): String {
        return when (range) {
            Range.TODAY -> WebsiteUsageDetailActivity.RANGE_TODAY
            Range.MONTH -> WebsiteUsageDetailActivity.RANGE_MONTH
            Range.YEAR -> WebsiteUsageDetailActivity.RANGE_YEAR
            Range.CUSTOM -> WebsiteUsageDetailActivity.RANGE_CUSTOM
            else -> WebsiteUsageDetailActivity.RANGE_WEEK
        }
    }

    private fun updateCustomRangeSummary() {
        val start = customRangeStartMillis
        val end = customRangeEndMillis
        if (currentRange != Range.CUSTOM || start == null || end == null) {
            b.customRangeSummary.isVisible = false
            return
        }
        b.customRangeSummary.isVisible = true
        val fmt = DateFormat.getDateInstance(DateFormat.SHORT)
        b.customRangeValue.text = getString(
            R.string.activity_history_range_custom_value,
            fmt.format(Date(start)),
            fmt.format(Date(end))
        )
    }

    private fun syncRangeChipUi(activeChipId: Int) {
        val activeBg = AccentColor.getAccentColorInt(this)
        val activeText = if (MaterialColors.isColorLight(activeBg)) Color.BLACK else Color.WHITE
        val inactiveBg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, 0)
        val inactiveText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, inactiveText)

        b.chipGroupRange.check(activeChipId)
        val buttons = listOf(b.chipToday, b.chipWeek, b.chipMonth, b.chipYear, b.chipCustom)
        buttons.forEach { button ->
            val active = button.id == activeChipId
            button.isChecked = active
            button.isCheckable = true
            button.isActivated = active
            button.backgroundTintList = ColorStateList.valueOf(if (active) activeBg else inactiveBg)
            button.shapeAppearanceModel = button.shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(dp(4).toFloat())
                .build()
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

    private fun updateStatisticsProfileSubtitle() {
        b.toolbar.subtitle = if (filter == Filter.BLOCKED_ONLY) {
            currentProfileName?.let { getString(R.string.profile_active_fmt, it) }
        } else {
            null
        }
    }

    private fun refresh() {
        val range = currentRange

        val isWeb = b.toggleType.checkedButtonId == b.btnWeb.id
        isWebMode = isWeb
        if (isWeb) {
            fallbackDeviceRange = null
            fallbackDeviceSummary = null
            fallbackPromptShownForRange = null
        }
        b.recycler.isVisible = true
        b.webPlaceholder.isVisible = false
        b.permHint.isVisible = false
        b.btnOpenSettings.isVisible = false

        val requestVersion = ++refreshVersion
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                buildRefreshData(range, isWeb)
            }
            if (requestVersion != refreshVersion) return@launch
            applyRefreshData(range, isWeb, data)
        }
    }

    private fun buildRefreshData(range: Range, isWeb: Boolean): RefreshData {
        val hasA11y = PermissionUtils.isAccessibilityServiceEnabled(this, SwitchlyAccessibilityService::class.java)
        val profile = ProfileStore.getCurrent(this)

        if (isWeb) {
            val summary = when (range) {
                Range.TODAY -> WebUsageRepo.getTodaySummary(this)
                Range.WEEK -> WebUsageRepo.getLastNDaysSummary(this, 7)
                Range.MONTH -> WebUsageRepo.getThisMonthSummary(this)
                Range.YEAR -> WebUsageRepo.getThisYearSummary(this)
                Range.CUSTOM -> customRangeStartMillis?.let { start ->
                    customRangeEndMillis?.let { end -> WebUsageRepo.getDateRangeSummary(this, start, end) }
                } ?: UsageSummary(0L, emptyList())
            }
            val blocked = DomainBlockStore.getDomains(this).map { it.trim() }.filter { it.isNotBlank() }
            val limited = DomainLimitStore.getDomainsWithLimit(this).map { it.trim() }.filter { it.isNotBlank() }
            return RefreshData(
                summary = summary,
                hasAccessibility = hasA11y,
                profile = profile,
                websiteRuleSet = (blocked + limited).toSet()
            )
        }

        val summary = when {
            range == Range.TODAY -> AppUsageRepo.getTodaySummary(this)
            fallbackDeviceRange == range -> fallbackDeviceSummary ?: UsageSummary(0L, emptyList())
            else -> when (range) {
                Range.TODAY -> AppUsageRepo.getTodaySummary(this)
                Range.WEEK -> AppUsageRepo.getLastNDaysSummary(this, 7)
                Range.MONTH -> AppUsageRepo.getThisMonthSummary(this)
                Range.YEAR -> AppUsageRepo.getThisYearSummary(this)
                Range.CUSTOM -> customRangeStartMillis?.let { start ->
                    customRangeEndMillis?.let { end -> AppUsageRepo.getDateRangeSummary(this, start, end) }
                } ?: UsageSummary(0L, emptyList())
            }
        }
        val blockedSet = if (profile.isNullOrBlank()) {
            emptySet()
        } else {
            buildSet {
                addAll(ProfileStore.getSelectedForProfileMode(this@AppWebsiteUsageActivity, profile))
                addAll(UsageLimitStore.getAllLimitedPackages(this@AppWebsiteUsageActivity, profile))
                addAll(SessionLimitStore.getAllLimitedPackages(this@AppWebsiteUsageActivity, profile))
                addAll(AttemptLimitStore.getAllLimitedPackages(this@AppWebsiteUsageActivity, profile))
            }
        }
        val metricPackages = buildSet {
            addAll(summary.topApps.map { it.packageName })
            addAll(blockedSet)
        }
        val metrics = buildAppUsageMetricsMap(metricPackages, range)
        return RefreshData(
            summary = summary,
            hasAccessibility = hasA11y,
            profile = profile,
            blockedSet = blockedSet,
            metricsByPackage = metrics
        )
    }

    private fun applyRefreshData(range: Range, isWeb: Boolean, data: RefreshData) {
        adapter.setDetailsCtaEnabled(true)
        b.toolbar.title = getString(R.string.statistics_usage_title)
        currentProfileName = data.profile
        updateStatisticsProfileSubtitle()
        b.statsPageSubtitle.isVisible = false
        b.totalTime.text = if (data.summary.totalTimeMs <= 0L) "—" else StatsFormat.prettyMsWithSeconds(data.summary.totalTimeMs)

        if (isWeb) {
            b.rowTapHint.isVisible = true
            b.rowTapHint.text = getString(R.string.usage_row_tap_hint_website)
            lastWebsites = data.summary.topApps
            currentWebsiteRuleSet = data.websiteRuleSet
            currentMetricsByPackage = emptyMap()
            applyAndShowCurrent()

            val visibleEmpty = adapter.itemCount == 0
            b.webPlaceholder.isVisible = visibleEmpty
            if (visibleEmpty) {
                b.webPlaceholder.text = when {
                    !data.hasAccessibility -> getString(R.string.usage_websites_no_accessibility)
                    filter == Filter.BLOCKED_ONLY && currentWebsiteRuleSet.isEmpty() -> getString(R.string.usage_websites_no_blocked)
                    else -> getString(R.string.usage_websites_no_data)
                }
            }

            b.permHint.isVisible = !data.hasAccessibility
            if (!data.hasAccessibility) {
                b.permHint.text = getString(R.string.usage_websites_no_accessibility)
            }
            b.btnOpenSettings.isVisible = !data.hasAccessibility
            b.btnOpenSettings.text = getString(R.string.onb_open)
            b.btnOpenSettings.setOnClickListener {
                AccessibilityDisclosure.openSettingsWithDisclosure(this)
            }
            return
        }

        b.rowTapHint.text = getString(R.string.usage_row_tap_hint_app)
        currentBlockedSet = data.blockedSet
        currentMetricsByPackage = data.metricsByPackage
        lastApps = data.summary.topApps
        applyAndShowCurrent()

        if (range != Range.TODAY && range != Range.CUSTOM && fallbackDeviceRange != range && data.summary.topApps.isEmpty() && data.hasAccessibility) {
            maybeShowDeviceFallbackDialog(range)
        }

        val visibleEmpty = adapter.itemCount == 0
        b.webPlaceholder.isVisible = visibleEmpty
        if (visibleEmpty) {
            b.webPlaceholder.text = when {
                !data.hasAccessibility -> getString(R.string.usage_apps_no_accessibility)
                filter == Filter.BLOCKED_ONLY && currentBlockedSet.isEmpty() -> getString(R.string.usage_apps_no_blocked)
                else -> getString(R.string.usage_apps_no_data)
            }
        }

        b.permHint.isVisible = !data.hasAccessibility && visibleEmpty
        if (!data.hasAccessibility && visibleEmpty) {
            b.permHint.text = getString(R.string.usage_apps_no_accessibility)
        }
        b.btnOpenSettings.isVisible = !data.hasAccessibility && visibleEmpty
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
            Range.CUSTOM -> UsageSummary(0L, emptyList())
        }
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

    private fun maybeShowDeviceFallbackDialog(range: Range) {
        if (fallbackPromptShownForRange == range) {
            return
        }
        fallbackPromptShownForRange = range
        val hasUsageAccess = UsageStatsRepo.hasUsageAccess(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.usage_switchly_fallback_title)
            .setMessage(R.string.usage_switchly_fallback_message)
            .setPositiveButton(if (hasUsageAccess) R.string.usage_switchly_fallback_positive else R.string.onb_open) { _, _ ->
                if (hasUsageAccess) {
                    loadDeviceFallbackSummary(range)
                } else {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
    }

    private fun loadDeviceFallbackSummary(range: Range) {
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) { getDeviceFallbackSummary(range) }
            fallbackDeviceRange = range
            fallbackDeviceSummary = summary
            refresh()
        }
    }

    private fun showSortFilterMenu(anchor: View) {
        val v = layoutInflater.inflate(R.layout.dialog_statistics_dropdown_sort_filter, FrameLayout(this), false)
        val filterDropdown = v.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownStatsPrimary)
        val sortDropdown = v.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownStatsSort)

        val isWeb = b.toggleType.checkedButtonId == b.btnWeb.id
        val filterOptions = listOf(
            Filter.ALL_APPS to getString(if (isWeb) R.string.stats_filter_all_websites else R.string.stats_filter_all_apps),
            Filter.BLOCKED_ONLY to getString(if (isWeb) R.string.stats_filter_blocked_only_websites else R.string.stats_filter_blocked_only)
        )
        val sortOptions = listOf(
            Pair(Sort.USED_TIME, SortDir.DESC) to getString(R.string.stats_sort_used_time_desc),
            Pair(Sort.USED_TIME, SortDir.ASC) to getString(R.string.stats_sort_used_time_asc),
            Pair(Sort.NAME_AZ, SortDir.ASC) to getString(R.string.stats_sort_az),
            Pair(Sort.NAME_ZA, SortDir.DESC) to getString(R.string.stats_sort_za)
        )

        var selectedFilter = filter
        var selectedSort = sort
        var selectedSortDir = sortDir

        filterDropdown.setAdapter(SwitchlyDropdownAdapter(this, filterOptions.map { it.second }))
        filterDropdown.setText(filterOptions.first { it.first == filter }.second, false)
        filterDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedFilter = filterOptions.getOrElse(position) { filterOptions.first() }.first
        }
        filterDropdown.setOnClickListener { filterDropdown.showDropDown() }

        sortDropdown.setAdapter(SwitchlyDropdownAdapter(this, sortOptions.map { it.second }))
        val currentSortLabel = sortOptions.firstOrNull { option ->
            when (sort) {
                Sort.USED_TIME -> option.first.first == sort && option.first.second == sortDir
                Sort.NAME_AZ, Sort.NAME_ZA -> option.first.first == sort
            }
        }?.second ?: sortOptions.first().second
        sortDropdown.setText(currentSortLabel, false)
        sortDropdown.setOnItemClickListener { _, _, position, _ ->
            val selected = sortOptions.getOrElse(position) { sortOptions.first() }.first
            selectedSort = selected.first
            selectedSortDir = selected.second
        }
        sortDropdown.setOnClickListener { sortDropdown.showDropDown() }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.stats_sort_filter_title))
            .setView(v)
            .setPositiveButton(getString(R.string.stats_apply)) { _, _ ->
                filter = selectedFilter
                sort = selectedSort
                sortDir = selectedSortDir
                updateStatisticsProfileSubtitle()
                applyAndShowCurrent()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
    }

    private fun applyAndShowCurrent() {
        updateStatisticsProfileSubtitle()
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
        fun intent(ctx: android.content.Context) = Intent(ctx, AppWebsiteUsageActivity::class.java)
    }
}
