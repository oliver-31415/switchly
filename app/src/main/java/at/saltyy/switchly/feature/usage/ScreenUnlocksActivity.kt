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
import at.saltyy.switchly.data.prefs.ScreenUnlockHistoryStore
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

class ScreenUnlocksActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: LinearLayout
    private var firstResume = true
    private var currentRange = Range.TODAY
    private var currentSort = Sort.NEWEST
    private var currentScope = Scope.ALL
    private var hideVeryShortUnlocks = false
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
        NEWEST,
        OLDEST,
        LONGEST,
        SHORTEST
    }

    private enum class Scope {
        ALL,
        CURRENT_PROFILE
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            minimumHeight = actionBarSize()
            title = getString(R.string.activity_entry_screen_unlocks)
            setNavigationIcon(R.drawable.keyboard_arrow_left_24)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            setBackgroundColor(AccentColor.getToolbarColor(this@ScreenUnlocksActivity))
        }
        UsageInfoAction.attach(this, toolbar, R.string.screen_unlocks_info_title, R.string.screen_unlocks_info_body)
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
            val accent = AccentColor.getAccentColorInt(this@ScreenUnlocksActivity)
            imageTintList = ColorStateList.valueOf(if (MaterialColors.isColorLight(accent)) Color.BLACK else Color.WHITE)
            contentDescription = getString(R.string.screen_unlocks_sort_filter_title)
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
        content.addView(messageCard(getString(R.string.usage_timeline_loading)))
        val ctx = applicationContext
        val range = currentRange
        val scope = currentScope
        val hasUsageAccess = UsageStatsRepo.hasUsageAccess(this)
        val profilePackages = if (scope == Scope.CURRENT_PROFILE) currentProfilePackages() else emptySet()
        Thread {
            val (from, to) = windowForRange(range)
            if (hasUsageAccess) {
                runCatching { StatsArchiveSync.sync(ctx) }
            }
            val profileAppSessions = if (hasUsageAccess && scope == Scope.CURRENT_PROFILE && profilePackages.isNotEmpty()) {
                UsageTimelineRepo.allAppSessions(ctx, from, to, limit = 0)
                    .filter { it.packageName in profilePackages }
            } else {
                emptyList()
            }
            val archived = ScreenUnlockHistoryStore.sessionsForRange(ctx, from, to)
                .map { UsageTimelineRepo.UnlockSession(it.startMs, it.endMs) }
            val live = if (hasUsageAccess) UsageTimelineRepo.screenUnlockSessions(ctx, from, to, limit = 0) else emptyList()
            if (live.isNotEmpty()) {
                ScreenUnlockHistoryStore.mergeSessions(ctx, live.map { ScreenUnlockHistoryStore.Session(it.startMs, it.endMs) })
            }
            val sessions = (archived + live)
                .groupBy { it.startMs }
                .map { (_, sameStart) -> sameStart.maxByOrNull { it.endMs } ?: sameStart.first() }
                .let { list ->
                    if (scope == Scope.CURRENT_PROFILE && hasUsageAccess) {
                        list.filter { unlock ->
                            profileAppSessions.any { app ->
                                app.endMs > unlock.startMs && app.startMs < unlock.endMs
                            }
                        }
                    } else {
                        list
                    }
                }
                .filter { !hideVeryShortUnlocks || it.durationMs >= 30_000L }
                .let { list ->
                    when (currentSort) {
                        Sort.NEWEST -> list.sortedByDescending { it.startMs }
                        Sort.OLDEST -> list.sortedBy { it.startMs }
                        Sort.LONGEST -> list.sortedByDescending { it.durationMs }
                        Sort.SHORTEST -> list.sortedBy { it.durationMs }
                    }
                }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (range != currentRange) return@runOnUiThread
                if (scope != currentScope) return@runOnUiThread
                if (sessions.isEmpty() && !hasUsageAccess) {
                    content.removeAllViews()
                    addRangeChips()
                    content.addView(messageCard(getString(R.string.screen_unlocks_permission_needed)).apply {
                        setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    })
                } else {
                    render(sessions)
                }
            }
        }.start()
    }

    private fun showSortFilterDialog() {
        val root = layoutInflater.inflate(R.layout.dialog_statistics_dropdown_sort_filter, FrameLayout(this), false)
        val scopeDropdown = root.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownStatsPrimary)
        val sortDropdown = root.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownStatsSort)
        val hideShort = root.findViewById<CheckBox>(R.id.cbStatsExtraFilter)

        root.findViewById<TextView>(R.id.tvStatsDropdownPrimaryLabel).text = getString(R.string.stats_scope_title)
        val scopeOptions = listOf(
            Scope.ALL to getString(R.string.stats_scope_all),
            Scope.CURRENT_PROFILE to getString(R.string.stats_scope_current_profile)
        )
        val sortOptions = listOf(
            Sort.NEWEST to getString(R.string.screen_unlocks_sort_newest),
            Sort.OLDEST to getString(R.string.screen_unlocks_sort_oldest),
            Sort.LONGEST to getString(R.string.screen_unlocks_sort_longest),
            Sort.SHORTEST to getString(R.string.screen_unlocks_sort_shortest)
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

        hideShort.text = getString(R.string.screen_unlocks_filter_short)
        hideShort.isChecked = hideVeryShortUnlocks
        hideShort.visibility = View.VISIBLE
        hideShort.buttonTintList = ColorStateList.valueOf(AccentColor.getAccentColorInt(this))

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.screen_unlocks_sort_filter_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                currentScope = selectedScope
                currentSort = selectedSort
                hideVeryShortUnlocks = hideShort.isChecked
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
            addAll(ProfileStore.getSelectedForProfileMode(this@ScreenUnlocksActivity, profile))
            addAll(UsageLimitStore.getAllLimitedPackages(this@ScreenUnlocksActivity, profile))
            addAll(SessionLimitStore.getAllLimitedPackages(this@ScreenUnlocksActivity, profile))
            addAll(AttemptLimitStore.getAllLimitedPackages(this@ScreenUnlocksActivity, profile))
        }
    }

    private fun render(sessions: List<UsageTimelineRepo.UnlockSession>) {
        content.removeAllViews()
        addRangeChips()
        if (sessions.isEmpty()) {
            content.addView(messageCard(getString(R.string.screen_unlocks_empty)))
            return
        }
        content.addView(summaryCard(
            getString(R.string.screen_unlocks_detail_summary_title),
            resources.getQuantityString(R.plurals.screen_unlocks_count, sessions.size, sessions.size)
        ))
        sessions.forEach { session ->
            content.addView(unlockRow(session))
        }
    }

    private fun unlockRow(session: UsageTimelineRepo.UnlockSession): ViewGroup {
        val card = baseCard()
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.lock_open_24)
            setColorFilter(AccentColor.getAccentColorInt(this@ScreenUnlocksActivity))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
            addView(TextView(this@ScreenUnlocksActivity).apply {
                text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(session.startMs))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@ScreenUnlocksActivity).apply {
                text = getString(R.string.screen_unlocks_row_summary, StatsFormat.prettyMsWithSeconds(session.durationMs))
                alpha = 0.75f
                textSize = 13f
            })
        })
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.keyboard_arrow_right_24)
            setColorFilter(ContextCompat.getColor(this@ScreenUnlocksActivity, android.R.color.darker_gray))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        card.addView(row)
        card.setOnClickListener {
            startActivity(ScreenUnlockDetailActivity.intent(this, session.startMs, session.endMs))
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
                    icon = ContextCompat.getDrawable(this@ScreenUnlocksActivity, R.drawable.calendar_month_24)
                    iconPadding = 0
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
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
        if (range == Range.TODAY || StatsPremiumGate.canUseExtendedStats(this)) {
            return true
        }
        StatsPremiumGate.show(this)
        return false
    }

    private fun addCustomRangeSummary() {
        if (currentRange != Range.CUSTOM) {
            return
        }
        val start = customRangeStartMillis ?: return
        val end = customRangeEndMillis ?: return
        val fmt = DateFormat.getDateInstance(DateFormat.SHORT)

        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(8))

            addView(TextView(this@ScreenUnlocksActivity).apply {
                text = getString(
                    R.string.activity_history_range_custom_value,
                    fmt.format(Date(start)),
                    fmt.format(Date(end))
                )
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                alpha = 0.82f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(MaterialButton(this@ScreenUnlocksActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
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
            currentRange = Range.CUSTOM
            load()
        }
        picker.addOnDismissListener { customRangePickerShowing = false }
        runCatching { picker.show(supportFragmentManager, "screen_unlocks_custom_range") }
            .onSuccess { UsageDatePickerAccentTint.apply(this, picker) }
            .onFailure { customRangePickerShowing = false }
    }

    private fun summaryCard(title: String, body: String): MaterialCardView {
        val card = baseCard()
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@ScreenUnlocksActivity).apply {
                text = title
                setTypeface(typeface, Typeface.BOLD)
                textSize = 16f
            })
            addView(TextView(this@ScreenUnlocksActivity).apply {
                text = body
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(AccentColor.getAccentColorInt(this@ScreenUnlocksActivity))
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

    private fun baseCard(): MaterialCardView {
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            radius = dp(22).toFloat()
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@ScreenUnlocksActivity, R.color.switchly_card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(this@ScreenUnlocksActivity, R.color.switchly_card_bg))
        }
    }

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
        fun intent(context: Context) = Intent(context, ScreenUnlocksActivity::class.java)
    }
}
