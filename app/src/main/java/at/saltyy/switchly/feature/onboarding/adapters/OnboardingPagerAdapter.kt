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

package at.saltyy.switchly.feature.onboarding.adapters

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.text.HtmlCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.onboarding.OnboardingPage
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.feature.usage.AppUsageAdapter
import at.saltyy.switchly.feature.usage.UsageStatsRepo
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.widgets.DonutChartView
import at.saltyy.switchly.ui.widgets.WeeklyBarChartView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class OnboardingPagerAdapter(
    private val activity: Activity,
    private val pages: List<OnboardingPage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int): Int {
        return when (pages[position].type) {
            OnboardingPage.Type.USAGE_SUMMARY -> 1
            else -> 0
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 1) {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding_usage_summary, parent, false)
            UsageSummaryVH(v)
        } else {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding_page, parent, false)
            StandardVH(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val page = pages[position]
        when (holder) {
            is UsageSummaryVH -> holder.bind(activity)
            is StandardVH -> holder.bind(activity, page)
        }
    }

    override fun getItemCount(): Int = pages.size

    class StandardVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconCard = itemView.findViewById<MaterialCardView>(R.id.iconCard)
        private val icon = itemView.findViewById<ImageView>(R.id.icon)
        private val title = itemView.findViewById<TextView>(R.id.title)
        private val desc = itemView.findViewById<TextView>(R.id.desc)
        private val badge = itemView.findViewById<TextView>(R.id.badge)
        private val btn = itemView.findViewById<MaterialButton>(R.id.btn_action)

        fun bind(activity: Activity, page: OnboardingPage) {
            title.text = page.title

            // Allow lightweight formatting (bold, line breaks, bullet points) in onboarding copy.
            desc.text = HtmlCompat.fromHtml(page.desc, HtmlCompat.FROM_HTML_MODE_COMPACT)

            val isQuickSetupPage = page.title == activity.getString(R.string.onb_quick_setup_title)
            // Keep onboarding copy centered and avoid ultra-wide lines on larger screens.
            desc.gravity = android.view.Gravity.CENTER
            desc.textAlignment = View.TEXT_ALIGNMENT_CENTER
            if (isQuickSetupPage) {
                val density = itemView.resources.displayMetrics.density
                desc.maxWidth = (340f * density).toInt()
                val sidePad = (10f * density).toInt()
                desc.setPaddingRelative(sidePad, 0, sidePad, 0)
                desc.setLineSpacing(0f, 1.2f)
                desc.includeFontPadding = false
            } else {
                desc.maxWidth = Int.MAX_VALUE
                desc.setPaddingRelative(0, 0, 0, 0)
                desc.setLineSpacing(0f, 1.0f)
                desc.includeFontPadding = true
            }

            if (page.iconRes != null) {
                icon.isVisible = true
                icon.setImageResource(page.iconRes)
            } else {
                icon.isVisible = false
            }

            badge.text = when (page.level) {
                OnboardingPage.Level.REQUIRED -> activity.getString(R.string.onb_badge_required)
                OnboardingPage.Level.RECOMMENDED -> activity.getString(R.string.onb_badge_recommended)
                OnboardingPage.Level.OPTIONAL -> activity.getString(R.string.onb_badge_optional)
                OnboardingPage.Level.INFO -> ""
            }
            badge.isVisible = page.level != OnboardingPage.Level.INFO

            val completed = page.completionCheck?.invoke(activity) == true
            val accent = AccentColor.getAccentColorInt(activity)
            val onAccent = readableOnColor(accent)

            // Keep onboarding hero icon card in sync with selected accent (including custom color).
            iconCard.setCardBackgroundColor(accent)
            icon.imageTintList = ColorStateList.valueOf(onAccent)
            icon.setColorFilter(onAccent)

            val hasAction = page.action != null && !page.actionLabel.isNullOrBlank()
            btn.isVisible = hasAction || completed

            if (completed) {
                btn.text = page.completedLabel ?: activity.getString(R.string.onb_granted)
                btn.isEnabled = false
                btn.alpha = 0.85f
                btn.backgroundTintList = ColorStateList.valueOf(accent)
                btn.setTextColor(onAccent)
                btn.setOnClickListener(null)
            } else if (hasAction) {
                btn.text = page.actionLabel
                btn.isEnabled = true
                btn.alpha = 1f
                btn.backgroundTintList = ColorStateList.valueOf(accent)
                btn.setTextColor(onAccent)
                btn.setOnClickListener { page.action?.invoke(activity) }
            } else {
                btn.setOnClickListener(null)
                btn.isEnabled = false
            }
        }
    }

    class UsageSummaryVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.title)
        private val subtitle = itemView.findViewById<TextView>(R.id.subtitle)
        private val summaryHeaderCard = itemView.findViewById<MaterialCardView>(R.id.summaryHeaderCard)
        private val topAppsCard = itemView.findViewById<MaterialCardView>(R.id.topAppsCard)
        private val trendCard = itemView.findViewById<MaterialCardView>(R.id.trendCard)
        private val totalTime = itemView.findViewById<TextView>(R.id.totalTime)
        private val donut = itemView.findViewById<DonutChartView>(R.id.donut)
        private val top3Total = itemView.findViewById<TextView>(R.id.top3Total)
        private val iconCard1 = itemView.findViewById<MaterialCardView>(R.id.iconCard1)
        private val iconCard2 = itemView.findViewById<MaterialCardView>(R.id.iconCard2)
        private val iconCard3 = itemView.findViewById<MaterialCardView>(R.id.iconCard3)
        private val icon1 = itemView.findViewById<ImageView>(R.id.icon1)
        private val icon2 = itemView.findViewById<ImageView>(R.id.icon2)
        private val icon3 = itemView.findViewById<ImageView>(R.id.icon3)
        private val listTop = itemView.findViewById<RecyclerView>(R.id.listTop)
        private val tvTrendTitle = itemView.findViewById<TextView>(R.id.tvTrendTitle)
        private val tvTrendApp = itemView.findViewById<TextView>(R.id.tvTrendApp)
        private val weeklyChart = itemView.findViewById<WeeklyBarChartView>(R.id.weeklyChart)
        private val btnGrant = itemView.findViewById<MaterialButton>(R.id.btnGrant)
        private val permHint = itemView.findViewById<TextView>(R.id.permHint)
        private val donutContainer = itemView.findViewById<ConstraintLayout>(R.id.donutContainer)

        private val adapter = AppUsageAdapter(onClick = null)

        init {
            listTop.layoutManager = LinearLayoutManager(itemView.context)
            listTop.adapter = adapter
        }

        fun bind(activity: Activity) {
            title.text = activity.getString(R.string.onb_quick_summary_title)
            subtitle.text = activity.getString(R.string.onb_quick_summary_desc)

            val accent = AccentColor.getAccentColorInt(activity)
            val onAccent = readableOnColor(accent)
            btnGrant.backgroundTintList = ColorStateList.valueOf(accent)
            btnGrant.setTextColor(onAccent)

            val hasAccess = UsageStatsRepo.hasUsageAccess(activity)

            btnGrant.isVisible = !hasAccess
            permHint.isVisible = !hasAccess

            if (!hasAccess) {
                subtitle.text = activity.getString(R.string.usage_access_needed_desc)
                summaryHeaderCard.isVisible = true
                topAppsCard.isVisible = false
                trendCard.isVisible = false

                totalTime.text = "—"
                top3Total.text = "—"
                donut.setData(emptyList())
                iconCard1.isVisible = false
                iconCard2.isVisible = false
                iconCard3.isVisible = false
                adapter.submit(emptyList())
                tvTrendTitle.isVisible = false
                tvTrendApp.isVisible = false
                weeklyChart.isVisible = false
                btnGrant.setOnClickListener {
                    activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                return
            }

            subtitle.text = activity.getString(R.string.onb_quick_summary_desc)

            summaryHeaderCard.isVisible = true
            topAppsCard.isVisible = true
            trendCard.isVisible = true

            val summary = UsageStatsRepo.getLast7DaysSummary(activity, topN = 20)
            totalTime.text = StatsFormat.prettyMsWithSeconds(summary.totalTimeMs)

            val top3 = summary.topApps.take(3)
            val top3Sum = top3.sumOf { it.timeMs }.coerceAtLeast(1L)
            top3Total.text = StatsFormat.prettyMsWithSeconds(top3Sum)

            val fractions = top3.map { it.timeMs.toFloat()/top3Sum.toFloat() }
            donut.setData(fractions)

            val rawIconAngles = mutableListOf<Float>()
            var start = -90f
            for (f in fractions) {
                val sweep = 360f * f.coerceAtLeast(0f)
                rawIconAngles.add(start + sweep/2f)
                start += sweep
            }
            val iconAngles = spreadAnglesForIcons(rawIconAngles)

            
            // Top-3 icons around donut.
            // We place them manually after layout to avoid occasional wrong positions on some devices when relying only on circle constraints.
            fun bindIcon(card: MaterialCardView, img: ImageView, idx: Int) {
                val item = top3.getOrNull(idx)
                if (item?.icon != null) {
                    card.isVisible = true
                    img.setImageDrawable(item.icon)
                } else {
                    card.isVisible = false
                }
            }
            bindIcon(iconCard1, icon1, 0)
            bindIcon(iconCard2, icon2, 1)
            bindIcon(iconCard3, icon3, 2)

            // Force deterministic icon placement on the donut arc.
            positionIconsAroundDonut(iconAngles)

            // List: show top 5 (donut stays top 3)
            val topList = summary.topApps.take(5)
            adapter.submit(topList)

            // Trend: show last 7 days for the #1 app
            val top1 = summary.topApps.firstOrNull()
            if (top1 != null) {
                tvTrendTitle.isVisible = true
                tvTrendApp.isVisible = true
                weeklyChart.isVisible = true
                tvTrendApp.text = top1.label
                val perDay = UsageStatsRepo.getLast7DaysPerDay(activity, top1.packageName)
                weeklyChart.setShowWeekdayLabels(true)
                weeklyChart.setLabelColorOverride(ContextCompat.getColor(activity, R.color.onb_summary_text_on_dark))
                weeklyChart.setValues(perDay)
            } else {
                tvTrendTitle.isVisible = false
                tvTrendApp.isVisible = false
                weeklyChart.isVisible = false
            }
        }

        private fun spreadAnglesForIcons(raw: List<Float>): List<Float> {
            if (raw.size <= 1) return raw

            val minSep = 34f
            val a = raw.toMutableList()

            // Unwrap to monotonic sequence first.
            for (i in 1 until a.size) {
                while (a[i] <= a[i - 1]) a[i] += 360f
            }

            // Enforce minimum spacing to avoid overlap.
            for (i in 1 until a.size) {
                val need = a[i - 1] + minSep
                if (a[i] < need) a[i] = need
            }

            // Keep set inside one circle.
            val first = a.first()
            val maxLast = first + 360f - minSep
            if (a.last() > maxLast) {
                val step = ((maxLast - first)/(a.size - 1)).coerceAtLeast(24f)
                for (i in 1 until a.size) {
                    a[i] = a[i - 1] + step
                }
            }

            return a.map { angle ->
                var n = angle
                while (n < 0f) n += 360f
                while (n >= 360f) n -= 360f
                n
            }
        }

        private fun positionIconsAroundDonut(iconAngles: List<Float>) {
            val cards = listOf(iconCard1, iconCard2, iconCard3)
            donutContainer.doOnLayout {
                val donutSize = min(donut.width, donut.height).toFloat()
                if (donutSize <= 0f) return@doOnLayout

                // Cards are constrained to the donut center in XML.
                // We only apply translations, so ConstraintLayout wont re-layout them into unexpected places.
                val preferred = (donutSize/2f) + dp(6f)

                val pad = dp(4f)
                val maxR = run {
                    val sample = cards.firstOrNull { it.isVisible } ?: cards.first()
                    val half = ((sample.width.takeIf { it > 0 } ?: (dp(48f)).toInt()).toFloat())/2f
                    val maxX = (donutContainer.width/2f) - half - pad
                    val maxY = (donutContainer.height/2f) - half - pad
                    min(maxX, maxY).coerceAtLeast(donutSize * 0.28f)
                }
                val radius = min(preferred, maxR)

                cards.forEachIndexed { idx, card ->
                    if (!card.isVisible) return@forEachIndexed

                    val angle = iconAngles.getOrNull(idx) ?: return@forEachIndexed
                    val rad = Math.toRadians(angle.toDouble())

                    val dx = cos(rad).toFloat() * radius
                    val dy = sin(rad).toFloat() * radius

                    card.translationX = dx
                    card.translationY = dy
                    card.bringToFront()
                }
            }
        }

        private fun dp(v: Float): Float {
            return v * itemView.resources.displayMetrics.density
        }

    }
}

private fun readableOnColor(color: Int): Int {
    val black = ColorUtils.calculateContrast(Color.BLACK, color)
    val white = ColorUtils.calculateContrast(Color.WHITE, color)
    return if (black >= white) Color.BLACK else Color.WHITE
}
