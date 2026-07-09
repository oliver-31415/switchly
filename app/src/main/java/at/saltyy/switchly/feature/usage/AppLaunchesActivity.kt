/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package at.saltyy.switchly.feature.usage

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AttemptLimitStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SessionLimitStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.SwitchlyDropdownAdapter
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class AppLaunchesActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: LinearLayout
    private var firstResume = true
    private var currentRange = Range.TODAY
    private var currentSort = Sort.LAUNCHES
    private var currentScope = Scope.ALL
    private var hideSingleLaunchApps = false
    private var customRangeStartMillis: Long? = null
    private var customRangeEndMillis: Long? = null
    private var customRangePickerShowing = false

    private enum class Range(val labelRes: Int, val queryName: String) {
        TODAY(R.string.stats_range_today, "today"),
        WEEK(R.string.stats_range_week, "week"),
        MONTH(R.string.stats_range_month, "month"),
        YEAR(R.string.stats_range_year, "year"),
        CUSTOM(R.string.activity_history_range_custom, "custom")
    }

    private enum class Sort {
        LAUNCHES,
        TIME,
        NAME
    }

    private enum class Scope {
        ALL,
        CURRENT_PROFILE
    }

    private data class LaunchAppSummary(
        val packageName: String,
        val label: String,
        val launchCount: Int,
        val totalMs: Long
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            minimumHeight = actionBarSize()
            title = getString(R.string.insights_app_launches)
            setNavigationIcon(R.drawable.arrow_back_ios_24)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            setBackgroundColor(AccentColor.getToolbarColor(this@AppLaunchesActivity))
        }
        root.addView(AppBarLayout(this).apply {
            fitsSystemWindows = true
            addView(toolbar, AppBarLayout.LayoutParams(
                AppBarLayout.LayoutParams.MATCH_PARENT,
                AppBarLayout.LayoutParams.WRAP_CONTENT
            ))
        })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(96))
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        val coordinator = CoordinatorLayout(this)
        coordinator.addView(root, CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
            CoordinatorLayout.LayoutParams.MATCH_PARENT
        ))
        coordinator.addView(FloatingActionButton(this).apply {
            setImageResource(R.drawable.tune_24)
            val accent = AccentColor.getAccentColorInt(this@AppLaunchesActivity)
            imageTintList = ColorStateList.valueOf(if (MaterialColors.isColorLight(accent)) Color.BLACK else Color.WHITE)
            contentDescription = getString(R.string.app_launches_sort_filter_title)
            setOnClickListener { showSortFilterDialog() }
            useCompatPadding = true
        }, CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = dp(16)
            bottomMargin = dp(96)
        })
        setContentView(coordinator)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        updateScopeSubtitle()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            return
        }
        if (::content.isInitialized) load()
    }

    private fun load() {
        content.removeAllViews()
        addRangeChips()
        if (!UsageStatsRepo.hasUsageAccess(this)) {
            content.addView(messageCard(getString(R.string.usage_timeline_permission_needed)).apply {
                setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            })
            return
        }

        content.addView(messageCard(getString(R.string.usage_timeline_loading)))
        val ctx = applicationContext
        val range = currentRange
        val scope = currentScope
        val profilePackages = if (scope == Scope.CURRENT_PROFILE) currentProfilePackages() else emptySet()
        Thread {
            val (from, to) = windowForRange(range)
            val sessions = UsageTimelineRepo.allAppSessions(ctx, from, to, limit = 0)
                .let { list ->
                    if (scope == Scope.CURRENT_PROFILE) list.filter { it.packageName in profilePackages } else list
                }
            val summaries = sessions
                .groupBy { it.packageName }
                .map { (pkg, appSessions) ->
                    LaunchAppSummary(
                        packageName = pkg,
                        label = appLabel(pkg),
                        launchCount = appSessions.size,
                        totalMs = appSessions.sumOf { it.durationMs }
                    )
                }
            val visibleSummaries = summaries
                .filter { !hideSingleLaunchApps || it.launchCount > 1 }
                .let { list ->
                    when (currentSort) {
                        Sort.LAUNCHES -> list.sortedWith(compareByDescending<LaunchAppSummary> { it.launchCount }.thenBy { it.label.lowercase() })
                        Sort.TIME -> list.sortedWith(compareByDescending<LaunchAppSummary> { it.totalMs }.thenBy { it.label.lowercase() })
                        Sort.NAME -> list.sortedBy { it.label.lowercase() }
                    }
                }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (range != currentRange) return@runOnUiThread
                if (scope != currentScope) return@runOnUiThread
                render(visibleSummaries, summaries.sumOf { it.launchCount })
            }
        }.start()
    }

    private fun showSortFilterDialog() {
        val root = layoutInflater.inflate(R.layout.dialog_statistics_dropdown_sort_filter, FrameLayout(this), false)
        val scopeDropdown = root.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownStatsPrimary)
        val sortDropdown = root.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownStatsSort)
        val hideSingle = root.findViewById<CheckBox>(R.id.cbStatsExtraFilter)

        root.findViewById<TextView>(R.id.tvStatsDropdownPrimaryLabel).text = getString(R.string.stats_scope_title)
        val scopeOptions = listOf(
            Scope.ALL to getString(R.string.stats_scope_all),
            Scope.CURRENT_PROFILE to getString(R.string.stats_scope_current_profile)
        )
        val sortOptions = listOf(
            Sort.LAUNCHES to getString(R.string.app_launches_sort_launches),
            Sort.TIME to getString(R.string.app_launches_sort_time),
            Sort.NAME to getString(R.string.app_launches_sort_name)
        )
        var selectedScope = currentScope
        var selectedSort = currentSort

        scopeDropdown.setAdapter(SwitchlyDropdownAdapter(this, scopeOptions.map { it.second }))
        scopeDropdown.setText(scopeOptions.first { it.first == currentScope }.second, false)
        scopeDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedScope = scopeOptions.getOrElse(position) { scopeOptions.first() }.first
        }
        scopeDropdown.setOnClickListener { scopeDropdown.showDropDown() }

        sortDropdown.setAdapter(SwitchlyDropdownAdapter(this, sortOptions.map { it.second }))
        sortDropdown.setText(sortOptions.first { it.first == currentSort }.second, false)
        sortDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedSort = sortOptions.getOrElse(position) { sortOptions.first() }.first
        }
        sortDropdown.setOnClickListener { sortDropdown.showDropDown() }

        hideSingle.text = getString(R.string.app_launches_filter_single)
        hideSingle.isChecked = hideSingleLaunchApps
        hideSingle.visibility = View.VISIBLE
        hideSingle.buttonTintList = ColorStateList.valueOf(AccentColor.getAccentColorInt(this))

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_launches_sort_filter_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                currentScope = selectedScope
                currentSort = selectedSort
                hideSingleLaunchApps = hideSingle.isChecked
                updateScopeSubtitle()
                load()
            }
            .showAccented()
    }

    private fun updateScopeSubtitle() {
        toolbar.subtitle = if (currentScope == Scope.CURRENT_PROFILE) {
            ProfileStore.getCurrent(this)?.let { getString(R.string.profile_active_fmt, it) }
        } else {
            null
        }
    }

    private fun currentProfilePackages(): Set<String> {
        val profile = ProfileStore.getCurrent(this)?.takeIf { it.isNotBlank() } ?: return emptySet()
        return buildSet {
            addAll(ProfileStore.getSelectedForProfileMode(this@AppLaunchesActivity, profile))
            addAll(UsageLimitStore.getAllLimitedPackages(this@AppLaunchesActivity, profile))
            addAll(SessionLimitStore.getAllLimitedPackages(this@AppLaunchesActivity, profile))
            addAll(AttemptLimitStore.getAllLimitedPackages(this@AppLaunchesActivity, profile))
        }
    }

    private fun render(summaries: List<LaunchAppSummary>, totalLaunches: Int) {
        content.removeAllViews()
        addRangeChips()
        if (summaries.isEmpty()) {
            content.addView(messageCard(getString(R.string.app_launches_empty)))
            return
        }

        content.addView(summaryCard(
            title = getString(R.string.app_launches_total_title),
            body = resources.getQuantityString(R.plurals.app_launches_count, totalLaunches, totalLaunches)
        ))
        content.addView(sectionTitle(getString(R.string.app_launches_overview_title)))
        summaries.forEach { summary ->
            content.addView(appRow(summary))
        }
    }

    private fun appRow(summary: LaunchAppSummary): ViewGroup {
        val card = baseCard()
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        row.addView(ImageView(this).apply {
            setImageDrawable(appIcon(summary.packageName))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
            addView(TextView(this@AppLaunchesActivity).apply {
                text = summary.label
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@AppLaunchesActivity).apply {
                text = getString(
                    R.string.app_launch_detail_summary,
                    resources.getQuantityString(R.plurals.app_launches_count, summary.launchCount, summary.launchCount),
                    StatsFormat.prettyMsWithSeconds(summary.totalMs)
                )
                alpha = 0.75f
                textSize = 13f
            })
        })
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.arrow_forward_ios_24)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@AppLaunchesActivity, android.R.color.darker_gray))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        card.addView(row)
        card.setOnClickListener {
            val (from, to) = windowForRange(currentRange)
            startActivity(AppLaunchDetailActivity.intent(this, summary.packageName, summary.label, currentRange.queryName, from, to))
        }
        return card
    }

    private fun addRangeChips() {
        val ids = mutableMapOf<Int, Range>()
        var checkedId = View.NO_ID
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        Range.values().forEach { range ->
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = if (range == Range.CUSTOM) "" else getString(range.labelRes)
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(4), 0, dp(4), 0)
                if (range == Range.CUSTOM) {
                    contentDescription = getString(R.string.activity_history_range_custom)
                    icon = ContextCompat.getDrawable(this@AppLaunchesActivity, R.drawable.calendar_month_24)
                    iconPadding = 0
                    setPadding(dp(8), 0, dp(8), 0)
                    gravity = Gravity.CENTER
                }
                isCheckable = true
                minHeight = dp(40)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(4)
                setAllCaps(false)
                layoutParams = if (range == Range.CUSTOM) {
                    LinearLayout.LayoutParams(dp(44), dp(40))
                } else {
                    LinearLayout.LayoutParams(0, dp(40), 1f)
                }
            }
            ids[button.id] = range
            if (range == currentRange) checkedId = button.id
            group.addView(button)
        }
        if (checkedId != View.NO_ID) group.check(checkedId)
        ids.forEach { (buttonId, range) ->
            group.findViewById<MaterialButton>(buttonId)?.let { styleRangeButton(it, range == currentRange) }
        }
        group.addOnButtonCheckedListener { _, checkedButtonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selected = ids[checkedButtonId] ?: return@addOnButtonCheckedListener
            if (selected == currentRange) return@addOnButtonCheckedListener
            if (!ensureRangeAllowed(selected)) {
                load()
                return@addOnButtonCheckedListener
            }
            if (selected == Range.CUSTOM) {
                showCustomRangePicker()
                return@addOnButtonCheckedListener
            }
            currentRange = selected
            load()
        }
        content.addView(group)
        addCustomRangeSummary()
    }

    private fun ensureRangeAllowed(range: Range): Boolean {
        if (range == Range.TODAY || StatsPremiumGate.canUseExtendedStats(this)) return true
        StatsPremiumGate.show(this)
        return false
    }

    private fun addCustomRangeSummary() {
        if (currentRange != Range.CUSTOM) return
        val start = customRangeStartMillis ?: return
        val end = customRangeEndMillis ?: return
        val fmt = DateFormat.getDateInstance(DateFormat.SHORT)

        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(8))

            addView(TextView(this@AppLaunchesActivity).apply {
                text = getString(
                    R.string.activity_history_range_custom_value,
                    fmt.format(Date(start)),
                    fmt.format(Date(end))
                )
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                alpha = 0.82f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(MaterialButton(this@AppLaunchesActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.stats_range_clear)
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(36)
                insetTop = 0
                insetBottom = 0
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    customRangeStartMillis = null
                    customRangeEndMillis = null
                    currentRange = Range.TODAY
                    load()
                }
            })
        })
    }

    private fun styleRangeButton(button: MaterialButton, active: Boolean) {
        val activeBg = AccentColor.getAccentColorInt(this)
        val activeText = if (MaterialColors.isColorLight(activeBg)) Color.BLACK else Color.WHITE
        val inactiveBg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, 0)
        val inactiveText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, inactiveText)
        button.backgroundTintList = ColorStateList.valueOf(if (active) activeBg else inactiveBg)
        button.setTextColor(if (active) activeText else inactiveText)
        button.iconTint = ColorStateList.valueOf(if (active) activeText else inactiveText)
        button.strokeColor = ColorStateList.valueOf(if (active) activeBg else outline)
        button.strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        button.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(activeBg, 0x35))
    }

    private fun windowForRange(range: Range): Pair<Long, Long> {
        if (range != Range.CUSTOM) {
            val (from, to) = UsageTimelineRepo.windowForRange(range.queryName)
            return Pair(from, to)
        }
        return Pair(customRangeStartMillis ?: startOfTodayMillis(), customRangeEndMillis ?: System.currentTimeMillis())
    }

    private fun showCustomRangePicker() {
        if (!ensureRangeAllowed(Range.CUSTOM)) {
            load()
            return
        }
        if (customRangePickerShowing || supportFragmentManager.isStateSaved) return
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
            currentRange = Range.CUSTOM
            load()
        }
        picker.addOnDismissListener { customRangePickerShowing = false }
        runCatching { picker.show(supportFragmentManager, "app_launches_custom_range") }
            .onSuccess { UsageDatePickerAccentTint.apply(this, picker) }
            .onFailure { customRangePickerShowing = false }
    }

    private fun summaryCard(title: String, body: String): MaterialCardView {
        val card = baseCard()
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@AppLaunchesActivity).apply {
                text = title
                setTypeface(typeface, Typeface.BOLD)
                textSize = 16f
            })
            addView(TextView(this@AppLaunchesActivity).apply {
                text = body
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(AccentColor.getAccentColorInt(this@AppLaunchesActivity))
            })
        })
        return card
    }

    private fun messageCard(message: String): MaterialCardView {
        val card = baseCard()
        card.addView(TextView(this).apply {
            text = message
            textSize = 14f
            alpha = 0.82f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
        return card
    }

    private fun sectionTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun baseCard(): MaterialCardView {
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            radius = dp(22).toFloat()
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@AppLaunchesActivity, R.color.switchly_card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(this@AppLaunchesActivity, R.color.switchly_card_bg))
        }
    }

    private fun appIcon(packageName: String) =
        runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
            ?: ContextCompat.getDrawable(this, R.drawable.apps_24)

    private fun appLabel(packageName: String): String =
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

    private fun actionBarSize(): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)
        return android.util.TypedValue.complexToDimensionPixelSize(typedValue.data, resources.displayMetrics)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        fun intent(context: Context) = Intent(context, AppLaunchesActivity::class.java)
    }
}
