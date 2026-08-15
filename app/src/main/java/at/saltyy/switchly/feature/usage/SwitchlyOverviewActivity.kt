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
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.setPadding
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.ActiveDurationStore
import at.saltyy.switchly.data.prefs.AppLaunchCountStore
import at.saltyy.switchly.data.prefs.BarcodeScanCountStore
import at.saltyy.switchly.data.prefs.BlockAttemptStore
import at.saltyy.switchly.data.prefs.EmergencyUnlockCountStore
import at.saltyy.switchly.data.prefs.LimitHitCountStore
import at.saltyy.switchly.data.prefs.NfcScanCountStore
import at.saltyy.switchly.data.prefs.QrScanCountStore
import at.saltyy.switchly.data.prefs.ScheduleExecutionCountStore
import at.saltyy.switchly.data.prefs.SwitchlyActionCountStore
import at.saltyy.switchly.data.prefs.TempEnableCountStore
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat
import java.util.Calendar

class SwitchlyOverviewActivity : AppCompatActivity() {

    private enum class Range {
        TODAY,
        WEEK,
        MONTH,
        YEAR,
        OVERALL,
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var rangeGroup: MaterialButtonToggleGroup
    private lateinit var scansCardContent: LinearLayout
    private lateinit var activityCardContent: LinearLayout
    private lateinit var actionsCardContent: LinearLayout
    private lateinit var schedulesCardContent: LinearLayout

    private val rangeButtons: MutableMap<Range, MaterialButton> = linkedMapOf()
    private var selectedRange: Range = Range.TODAY

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(at.saltyy.switchly.util.LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resolveColor(android.R.attr.colorBackground))
        }

        toolbar = MaterialToolbar(this).apply {
            minimumHeight = actionBarSize()
            title = getString(R.string.switchly_overview_title)
            setNavigationIcon(R.drawable.keyboard_arrow_left_24)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            setBackgroundColor(AccentColor.getToolbarColor(this@SwitchlyOverviewActivity))
            navigationIcon?.mutate()?.setTint(toolbarIconColor())
            menu.add(R.string.switchly_overview_info_title).apply {
                setIcon(R.drawable.info_24)
                icon?.mutate()?.setTint(toolbarIconColor())
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener {
                    showInfo()
                    true
                }
            }
        }
        root.addView(
            AppBarLayout(this).apply {
                fitsSystemWindows = true
                addView(
                    toolbar,
                    AppBarLayout.LayoutParams(
                        AppBarLayout.LayoutParams.MATCH_PARENT,
                        AppBarLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        scroll.addView(content)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        rangeGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
            }
        }
        addRangeButton(Range.TODAY, R.string.stats_range_today)
        addRangeButton(Range.WEEK, R.string.stats_range_week)
        addRangeButton(Range.MONTH, R.string.stats_range_month)
        addRangeButton(Range.YEAR, R.string.stats_range_year)
        addRangeButton(Range.OVERALL, R.string.switchly_overview_range_overall)
        content.addView(rangeGroup)

        content.addView(sectionTitle(R.string.switchly_overview_section_scans))
        val scansCard = statsCard().also { card ->
            scansCardContent = card.getChildAt(0) as LinearLayout
        }
        content.addView(scansCard, sectionCardLayoutParams())

        content.addView(sectionTitle(R.string.switchly_overview_section_activity).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(22)
        })
        val activityCard = statsCard().also { card ->
            activityCardContent = card.getChildAt(0) as LinearLayout
        }
        content.addView(activityCard, sectionCardLayoutParams())

        content.addView(sectionTitle(R.string.switchly_overview_section_actions).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(22)
        })
        val actionsCard = statsCard().also { card ->
            actionsCardContent = card.getChildAt(0) as LinearLayout
        }
        content.addView(actionsCard, sectionCardLayoutParams())

        content.addView(sectionTitle(R.string.switchly_overview_section_schedules).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(22)
        })
        val schedulesCard = statsCard().also { card ->
            schedulesCardContent = card.getChildAt(0) as LinearLayout
        }
        content.addView(schedulesCard, sectionCardLayoutParams())

        content.addView(TextView(this).apply {
            text = getString(R.string.switchly_overview_storage_note)
            textSize = 13f
            alpha = 0.72f
            setPadding(0, dp(16), 0, dp(12))
        })

        setContentView(root)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)

        val todayButton = rangeButtons.getValue(Range.TODAY)
        rangeGroup.check(todayButton.id)
        syncRangeButtonUi()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun addRangeButton(range: Range, labelRes: Int) {
        val button = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            id = View.generateViewId()
            text = getString(labelRes)
            isCheckable = true
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(40)
            minimumHeight = dp(40)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(3), 0, dp(3), 0)
            cornerRadius = dp(4)
            setAllCaps(false)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            setOnClickListener {
                selectRange(range)
            }
        }
        rangeButtons[range] = button
        rangeGroup.addView(button)
    }

    private fun selectRange(requestedRange: Range) {
        if (requestedRange != Range.TODAY && !StatsPremiumGate.canUseExtendedStats(this)) {
            rangeGroup.check(rangeButtons.getValue(selectedRange).id)
            syncRangeButtonUi()
            StatsPremiumGate.show(this)
            return
        }

        selectedRange = requestedRange
        rangeGroup.check(rangeButtons.getValue(selectedRange).id)
        syncRangeButtonUi()
        refresh()
    }

    private fun syncRangeButtonUi() {
        val activeBackground = AccentColor.getAccentColorInt(this)
        val activeText = if (MaterialColors.isColorLight(activeBackground)) {
            Color.BLACK
        } else {
            Color.WHITE
        }
        val inactiveBackground = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceVariant,
            Color.TRANSPARENT
        )
        val inactiveText = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
            Color.WHITE
        )
        val outline = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOutline,
            inactiveText
        )

        for ((range, button) in rangeButtons) {
            val active = range == selectedRange
            button.isChecked = active
            button.isActivated = active
            button.backgroundTintList = ColorStateList.valueOf(
                if (active) activeBackground else inactiveBackground
            )
            button.setTextColor(if (active) activeText else inactiveText)
            button.strokeColor = ColorStateList.valueOf(
                if (active) activeBackground else outline
            )
            button.strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
            button.rippleColor = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(activeBackground, 0x35)
            )
            button.jumpDrawablesToCurrentState()
        }
    }

    private fun refresh() {
        scansCardContent.removeAllViews()
        addStatRow(
            parent = scansCardContent,
            iconRes = R.drawable.nfc_24,
            labelRes = R.string.switchly_overview_nfc_scans,
            value = countForRange(
                today = { NfcScanCountStore.getToday(this) },
                week = { NfcScanCountStore.getForLastNDays(this, 7) },
                month = { year, month -> NfcScanCountStore.getForMonth(this, year, month) },
                year = { year -> NfcScanCountStore.getForYear(this, year) },
                overall = { NfcScanCountStore.getOverall(this) }
            )
        )
        addStatRow(
            parent = scansCardContent,
            iconRes = R.drawable.qr_code_24,
            labelRes = R.string.switchly_overview_qr_scans,
            value = countForRange(
                today = { QrScanCountStore.getToday(this) },
                week = { QrScanCountStore.getForLastNDays(this, 7) },
                month = { year, month -> QrScanCountStore.getForMonth(this, year, month) },
                year = { year -> QrScanCountStore.getForYear(this, year) },
                overall = { QrScanCountStore.getOverall(this) }
            )
        )
        addStatRow(
            parent = scansCardContent,
            iconRes = R.drawable.barcode_24,
            labelRes = R.string.switchly_overview_barcode_scans,
            value = countForRange(
                today = { BarcodeScanCountStore.getToday(this) },
                week = { BarcodeScanCountStore.getForLastNDays(this, 7) },
                month = { year, month -> BarcodeScanCountStore.getForMonth(this, year, month) },
                year = { year -> BarcodeScanCountStore.getForYear(this, year) },
                overall = { BarcodeScanCountStore.getOverall(this) }
            ),
            last = true
        )

        activityCardContent.removeAllViews()
        addStatRow(
            parent = activityCardContent,
            iconRes = R.drawable.timer_24,
            labelRes = R.string.switchly_overview_active_time,
            valueText = StatsFormat.prettyMsWithSeconds(activeTimeForRange())
        )
        addStatRow(
            parent = activityCardContent,
            iconRes = R.drawable.apps_24,
            labelRes = R.string.switchly_overview_app_launches,
            value = countForRange(
                today = { AppLaunchCountStore.getTotalToday(this) },
                week = { AppLaunchCountStore.getTotalForLastNDays(this, 7) },
                month = { year, month -> AppLaunchCountStore.getTotalForMonth(this, year, month) },
                year = { year -> AppLaunchCountStore.getTotalForYear(this, year) },
                overall = { AppLaunchCountStore.getTotalOverall(this) }
            )
        )
        addStatRow(
            parent = activityCardContent,
            iconRes = R.drawable.security_24,
            labelRes = R.string.switchly_overview_blocks,
            value = countForRange(
                today = { BlockAttemptStore.getTodayTotal(this) },
                week = { BlockAttemptStore.getForLastNDaysTotal(this, 7) },
                month = { year, month -> BlockAttemptStore.getForMonthTotal(this, year, month) },
                year = { year -> BlockAttemptStore.getForYearTotal(this, year) },
                overall = { BlockAttemptStore.getOverallTotal(this) }
            )
        )
        addStatRow(
            parent = activityCardContent,
            iconRes = R.drawable.bar_chart_24,
            labelRes = R.string.switchly_overview_limits_reached,
            value = countForRange(
                today = { LimitHitCountStore.getToday(this) },
                week = { LimitHitCountStore.getForLastNDays(this, 7) },
                month = { year, month -> LimitHitCountStore.getForMonth(this, year, month) },
                year = { year -> LimitHitCountStore.getForYear(this, year) },
                overall = { LimitHitCountStore.getOverall(this) }
            ),
            last = true
        )

        actionsCardContent.removeAllViews()
        addStatRow(
            parent = actionsCardContent,
            iconRes = R.drawable.toggle_on_24,
            labelRes = R.string.switchly_overview_enabled,
            value = actionCount(SwitchlyActionCountStore.Action.ENABLE)
        )
        addStatRow(
            parent = actionsCardContent,
            iconRes = R.drawable.toggle_off_24,
            labelRes = R.string.switchly_overview_disabled,
            value = actionCount(SwitchlyActionCountStore.Action.DISABLE)
        )
        addStatRow(
            parent = actionsCardContent,
            iconRes = R.drawable.play_arrow_24,
            labelRes = R.string.switchly_overview_temporary_enables,
            value = countForRange(
                today = { TempEnableCountStore.getToday(this) },
                week = { TempEnableCountStore.getForLastNDays(this, 7) },
                month = { year, month -> TempEnableCountStore.getForMonth(this, year, month) },
                year = { year -> TempEnableCountStore.getForYear(this, year) },
                overall = { TempEnableCountStore.getOverall(this) }
            )
        )
        addStatRow(
            parent = actionsCardContent,
            iconRes = R.drawable.lock_open_24,
            labelRes = R.string.switchly_overview_emergency_unlocks,
            value = countForRange(
                today = { EmergencyUnlockCountStore.getToday(this) },
                week = { EmergencyUnlockCountStore.getForLastNDays(this, 7) },
                month = { year, month -> EmergencyUnlockCountStore.getForMonth(this, year, month) },
                year = { year -> EmergencyUnlockCountStore.getForYear(this, year) },
                overall = { EmergencyUnlockCountStore.getOverall(this) }
            ),
            last = true
        )

        schedulesCardContent.removeAllViews()
        addStatRow(
            parent = schedulesCardContent,
            iconRes = R.drawable.schedule_24,
            labelRes = R.string.switchly_overview_schedules_executed,
            value = countForRange(
                today = { ScheduleExecutionCountStore.getToday(this) },
                week = { ScheduleExecutionCountStore.getForLastNDays(this, 7) },
                month = { year, month -> ScheduleExecutionCountStore.getForMonth(this, year, month) },
                year = { year -> ScheduleExecutionCountStore.getForYear(this, year) },
                overall = { ScheduleExecutionCountStore.getOverall(this) }
            )
        )
        addStatRow(
            parent = schedulesCardContent,
            iconRes = R.drawable.toggle_on_24,
            labelRes = R.string.switchly_overview_schedule_enables,
            value = actionCount(SwitchlyActionCountStore.Action.SCHEDULE_ENABLE)
        )
        addStatRow(
            parent = schedulesCardContent,
            iconRes = R.drawable.toggle_off_24,
            labelRes = R.string.switchly_overview_schedule_disables,
            value = actionCount(SwitchlyActionCountStore.Action.SCHEDULE_DISABLE),
            last = true
        )
    }

    private fun activeTimeForRange(): Long {
        return when (selectedRange) {
            Range.TODAY -> ActiveDurationStore.todayMs(this)
            Range.WEEK -> ActiveDurationStore.lastNDaysMs(this, 7)
            Range.MONTH -> ActiveDurationStore.thisMonthMs(this)
            Range.YEAR -> ActiveDurationStore.thisYearMs(this)
            Range.OVERALL -> ActiveDurationStore.overallMs(this)
        }
    }

    private fun actionCount(action: SwitchlyActionCountStore.Action): Int {
        return countForRange(
            today = { SwitchlyActionCountStore.getToday(this, action) },
            week = { SwitchlyActionCountStore.getForLastNDays(this, action, 7) },
            month = { year, month ->
                SwitchlyActionCountStore.getForMonth(this, action, year, month)
            },
            year = { year -> SwitchlyActionCountStore.getForYear(this, action, year) },
            overall = { SwitchlyActionCountStore.getOverall(this, action) }
        )
    }

    private fun countForRange(
        today: () -> Int,
        week: () -> Int,
        month: (Int, Int) -> Int,
        year: (Int) -> Int,
        overall: () -> Int,
    ): Int {
        val calendar = Calendar.getInstance()
        return when (selectedRange) {
            Range.TODAY -> today()
            Range.WEEK -> week()
            Range.MONTH -> month(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1
            )
            Range.YEAR -> year(calendar.get(Calendar.YEAR))
            Range.OVERALL -> overall()
        }
    }

    private fun sectionTitle(textRes: Int): TextView {
        return TextView(this).apply {
            text = getString(textRes)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(
                MaterialColors.getColor(
                    this@SwitchlyOverviewActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.GRAY
                )
            )
            alpha = 0.72f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun statsCard(): MaterialCardView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        return MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = dp(1).toFloat()
            useCompatPadding = true
            setCardBackgroundColor(
                ContextCompat.getColor(this@SwitchlyOverviewActivity, R.color.switchly_card_bg)
            )
            strokeColor = ContextCompat.getColor(
                this@SwitchlyOverviewActivity,
                R.color.switchly_card_stroke
            )
            strokeWidth = dp(1)
            addView(content)
        }
    }

    private fun sectionCardLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
        }
    }

    private fun addStatRow(
        parent: LinearLayout,
        iconRes: Int,
        labelRes: Int,
        value: Int,
        last: Boolean = false,
    ) {
        addStatRow(
            parent = parent,
            iconRes = iconRes,
            labelRes = labelRes,
            valueText = NumberFormat.getIntegerInstance().format(value),
            last = last
        )
    }

    private fun addStatRow(
        parent: LinearLayout,
        iconRes: Int,
        labelRes: Int,
        valueText: String,
        last: Boolean = false,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(AccentColor.getAccentColorInt(this@SwitchlyOverviewActivity))
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        row.addView(TextView(this).apply {
            text = getString(labelRes)
            textSize = 15f
            setTextColor(
                MaterialColors.getColor(
                    this@SwitchlyOverviewActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.WHITE
                )
            )
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(14)
        })
        row.addView(TextView(this).apply {
            text = valueText
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AccentColor.getAccentColorInt(this@SwitchlyOverviewActivity))
        })
        parent.addView(row)

        if (!last) {
            parent.addView(View(this).apply {
                setBackgroundColor(
                    ContextCompat.getColor(this@SwitchlyOverviewActivity, R.color.switchly_card_stroke)
                )
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                marginStart = dp(52)
                marginEnd = dp(16)
            })
        }
    }

    private fun showInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.switchly_overview_info_title)
            .setMessage(R.string.switchly_overview_info_body)
            .setPositiveButton(android.R.string.ok, null)
            .showAccented()
    }

    private fun toolbarIconColor(): Int {
        return if (MaterialColors.isColorLight(AccentColor.getToolbarColor(this))) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }

    private fun resolveColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun actionBarSize(): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)
        return android.util.TypedValue.complexToDimensionPixelSize(
            typedValue.data,
            resources.displayMetrics
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, SwitchlyOverviewActivity::class.java)
        }
    }
}
