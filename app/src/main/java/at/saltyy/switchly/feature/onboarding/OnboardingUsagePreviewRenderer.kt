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

package at.saltyy.switchly.feature.onboarding

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import at.saltyy.switchly.R
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.feature.usage.AppUsage
import at.saltyy.switchly.feature.usage.AppUsageRepo
import at.saltyy.switchly.feature.usage.UsageSummary
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import kotlin.math.min
import kotlin.math.roundToInt

// Renders the optional seven-day usage report directly inside the onboarding pager.
object OnboardingUsagePreviewRenderer {

    private const val FEATURED_APP_COUNT = 7
    private const val CACHE_DURATION_MS = 60_000L

    @Volatile
    private var cachedSummary: UsageSummary? = null

    @Volatile
    private var cachedAtElapsedMs: Long = 0L

    fun prefetch(activity: Activity) {
        if (cachedSummaryIfFresh() != null) return
        val lifecycleOwner = activity as? LifecycleOwner ?: return
        lifecycleOwner.lifecycleScope.launch { loadSummary(activity) }
    }

    fun render(
        activity: Activity,
        container: LinearLayout,
        accent: Int
    ) {
        container.removeAllViews()
        container.isVisible = true

        val density = activity.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()

        val renderToken = Any()
        container.tag = renderToken

        val cached = cachedSummaryIfFresh()
        if (cached == null) {
            addLoadingState(activity, container, accent)
        }

        val lifecycleOwner = activity as? LifecycleOwner ?: return
        lifecycleOwner.lifecycleScope.launch {
            val summary = cached ?: loadSummary(activity)

            if (container.tag !== renderToken || activity.isFinishing || activity.isDestroyed) {
                return@launch
            }

            container.removeAllViews()
            if (summary == null || summary.topApps.isEmpty() || summary.totalTimeMs <= 0L) {
                addEmptyState(activity, container, accent)
                return@launch
            }

            val chartTop = summary.topApps
                .take(FEATURED_APP_COUNT)
                .map { app -> app.copy(icon = resolveUsageIcon(activity, app)) }
            addSummaryCard(
                activity = activity,
                container = container,
                totalTimeMs = summary.totalTimeMs,
                apps = chartTop,
                accent = accent
            )

            container.addView(TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16f) }
                text = activity.getString(R.string.onb_usage_preview_top_apps)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface))
                includeFontPadding = false
            })

            val colors = buildChartColors(container, accent)
            chartTop.forEachIndexed { index, app ->
                container.addView(
                    createUsageRow(
                        activity = activity,
                        label = app.label,
                        timeMs = app.timeMs,
                        icon = app.icon,
                        markerColor = colors[index.coerceAtMost(colors.lastIndex - 1)]
                    )
                )
            }

            val otherTimeMs = (summary.totalTimeMs - chartTop.sumOf { it.timeMs })
                .coerceAtLeast(0L)
            if (otherTimeMs > 0L) {
                container.addView(
                    createUsageRow(
                        activity = activity,
                        label = activity.getString(R.string.onb_usage_preview_other_apps),
                        timeMs = otherTimeMs,
                        icon = ContextCompat.getDrawable(activity, R.drawable.apps_24),
                        markerColor = colors.last(),
                        tintIcon = true
                    )
                )
            }
        }
    }

    private fun cachedSummaryIfFresh(): UsageSummary? {
        val summary = cachedSummary ?: return null
        return if (SystemClock.elapsedRealtime() - cachedAtElapsedMs <= CACHE_DURATION_MS) {
            summary
        } else {
            null
        }
    }

    private suspend fun loadSummary(activity: Activity): UsageSummary? {
        val fresh = cachedSummaryIfFresh()
        if (fresh != null) return fresh

        val summary = withContext(Dispatchers.IO) {
            runCatching {
                AppUsageRepo.getDeviceSummary(activity.applicationContext, 7, topN = 20)
            }.getOrNull()
        }
        if (summary != null) {
            cachedSummary = summary
            cachedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        return summary
    }

    private fun addLoadingState(
        activity: Activity,
        container: LinearLayout,
        accent: Int,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()
        val onSurface = MaterialColors.getColor(
            container,
            com.google.android.material.R.attr.colorOnSurface
        )

        val card = MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            radius = dp(18f).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1f)
            strokeColor = ColorUtils.setAlphaComponent(accent, 0x72)
            setCardBackgroundColor(ContextCompat.getColor(activity, R.color.switchly_card_bg))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(184f)
            setPadding(dp(20f), dp(24f), dp(20f), dp(24f))
        }
        content.addView(CircularProgressIndicator(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f))
            isIndeterminate = true
            indicatorSize = dp(48f)
            trackThickness = dp(4f)
            setIndicatorColor(accent)
        })
        content.addView(TextView(activity).apply {
            text = activity.getString(R.string.onb_usage_preview_loading_title)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(onSurface)
            includeFontPadding = false
            setPadding(0, dp(14f), 0, 0)
        })
        content.addView(TextView(activity).apply {
            text = activity.getString(R.string.onb_usage_preview_loading_desc)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ColorUtils.setAlphaComponent(onSurface, 0xAF))
            includeFontPadding = false
            setPadding(0, dp(6f), 0, 0)
        })
        card.addView(content)
        container.addView(card)
    }

    private fun addSummaryCard(
        activity: Activity,
        container: LinearLayout,
        totalTimeMs: Long,
        apps: List<AppUsage>,
        accent: Int
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()

        val card = MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            radius = dp(20f).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1f)
            strokeColor = ContextCompat.getColor(activity, R.color.switchly_card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(activity, R.color.switchly_card_bg))
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(14f))
        }

        val availableWidth = activity.resources.displayMetrics.widthPixels - dp(96f)
        val chartSize = min(dp(252f), availableWidth.coerceAtLeast(dp(210f)))
        val chartFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(chartSize, chartSize)
        }

        val chart = OnboardingUsageDonutView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val colors = buildChartColors(container, accent)
        val topFraction = apps.sumOf { it.percent.toDouble() }.toFloat().coerceIn(0f, 1f)
        val segments = apps.mapIndexed { index, app ->
            OnboardingUsageDonutView.Segment(
                fraction = app.percent,
                color = colors[index.coerceAtMost(colors.lastIndex - 1)],
                icon = app.icon,
                percentageLabel = formatChartPercent(app.percent)
            )
        }.toMutableList()
        val otherFraction = (1f - topFraction).coerceAtLeast(0f)
        if (otherFraction > 0.005f) {
            segments += OnboardingUsageDonutView.Segment(
                fraction = otherFraction,
                color = colors.last(),
                label = activity.getString(R.string.onb_usage_preview_other_apps),
                percentageLabel = formatChartPercent(otherFraction)
            )
        }
        chart.submitSegments(segments)
        chartFrame.addView(chart)

        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val center = LinearLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dp(128f), dp(128f), Gravity.CENTER)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        center.addView(TextView(activity).apply {
            text = StatsFormat.prettyMs(totalTimeMs)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(onSurface)
            includeFontPadding = false
        })
        center.addView(TextView(activity).apply {
            text = activity.getString(R.string.onb_usage_preview_total)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ColorUtils.setAlphaComponent(onSurface, 170))
            includeFontPadding = false
            setPadding(0, dp(3f), 0, 0)
        })
        chartFrame.addView(center)
        content.addView(chartFrame)

        val averageRow = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2f) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        averageRow.addView(TextView(activity).apply {
            text = activity.getString(R.string.onb_usage_preview_daily_average)
            textSize = 13f
            setTextColor(ColorUtils.setAlphaComponent(onSurface, 170))
            includeFontPadding = false
        })
        averageRow.addView(TextView(activity).apply {
            text = StatsFormat.prettyMs(totalTimeMs / 7L)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
            includeFontPadding = false
            setPadding(dp(6f), 0, 0, 0)
        })
        content.addView(averageRow)

        card.addView(content)
        container.addView(card)
    }

    private fun createUsageRow(
        activity: Activity,
        label: String,
        timeMs: Long,
        icon: Drawable?,
        markerColor: Int,
        tintIcon: Boolean = false
    ): View {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()
        val onSurface = MaterialColors.getColor(activity.window.decorView, com.google.android.material.R.attr.colorOnSurface)

        val card = MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
            radius = dp(17f).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1f)
            strokeColor = ContextCompat.getColor(activity, R.color.switchly_card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(activity, R.color.switchly_card_bg))
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68f)
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        }

        row.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(5f), dp(42f)).apply { marginEnd = dp(11f) }
            background = GradientDrawable().apply {
                cornerRadius = dp(4f).toFloat()
                setColor(markerColor)
            }
        })

        row.addView(ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(43f), dp(43f)).apply { marginEnd = dp(12f) }
            val rowIcon = icon?.constantState
                ?.newDrawable(activity.resources)
                ?.mutate()
                ?: icon
                ?: activity.packageManager.defaultActivityIcon
            setImageDrawable(rowIcon)
            if (tintIcon) {
                imageTintList = android.content.res.ColorStateList.valueOf(markerColor)
                setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = null
        })

        row.addView(TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        })

        row.addView(TextView(activity).apply {
            text = StatsFormat.prettyMs(timeMs)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setTextColor(onSurface)
            includeFontPadding = false
        })

        card.addView(row)
        return card
    }

    private fun addEmptyState(
        activity: Activity,
        container: LinearLayout,
        accent: Int
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()
        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)

        val card = MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            radius = dp(18f).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1f)
            strokeColor = ColorUtils.setAlphaComponent(accent, 120)
            setCardBackgroundColor(ColorUtils.setAlphaComponent(accent, 16))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(24f), dp(20f), dp(24f))
        }
        content.addView(ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56f), dp(56f))
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            setImageResource(R.drawable.apps_24)
            imageTintList = android.content.res.ColorStateList.valueOf(accent)
            contentDescription = null
        })
        content.addView(TextView(activity).apply {
            text = activity.getString(R.string.onb_usage_preview_empty_title)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(onSurface)
            includeFontPadding = false
            setPadding(0, dp(12f), 0, 0)
        })
        content.addView(TextView(activity).apply {
            text = activity.getString(R.string.onb_usage_preview_empty_desc)
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(ColorUtils.setAlphaComponent(onSurface, 175))
            includeFontPadding = false
            setPadding(0, dp(7f), 0, 0)
        })
        card.addView(content)
        container.addView(card)
    }

    private fun formatChartPercent(fraction: Float): String {
        val normalized = fraction.coerceIn(0f, 1f)
        val percent = (normalized * 100f).roundToInt()
        return if (normalized > 0f && percent == 0) "<1%" else "$percent%"
    }

    private fun resolveUsageIcon(activity: Activity, app: AppUsage): Drawable {
        val rawIcon = runCatching {
            activity.packageManager.getApplicationIcon(app.packageName)
        }.getOrNull() ?: app.icon ?: activity.packageManager.defaultActivityIcon

        val drawable = rawIcon.constantState
            ?.newDrawable(activity.resources)
            ?.mutate()
            ?: rawIcon.mutate()
        val size = (48f * activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val previousBounds = drawable.copyBounds()
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        drawable.bounds = previousBounds
        return bitmap.toDrawable(activity.resources)
    }

    private fun buildChartColors(anchorView: View, accent: Int): IntArray {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(accent, hsl)
        val isDarkAccent = ColorUtils.calculateLuminance(accent) < 0.24

        val featured = IntArray(FEATURED_APP_COUNT) { index ->
            if (index == 0) {
                accent
            } else {
                val hue = (hsl[0] + (index * 45f)) % 360f
                val saturationScale = if (index % 2 == 0) 0.88f else 0.98f
                val lightness = if (isDarkAccent) {
                    0.54f + ((index % 3) * 0.045f)
                } else {
                    0.42f + ((index % 3) * 0.045f)
                }
                ColorUtils.HSLToColor(
                    floatArrayOf(
                        hue,
                        (hsl[1] * saturationScale).coerceIn(0.44f, 0.94f),
                        lightness.coerceIn(0.34f, 0.72f)
                    )
                )
            }
        }

        val other = ColorUtils.blendARGB(
            MaterialColors.getColor(anchorView, com.google.android.material.R.attr.colorOnSurface),
            MaterialColors.getColor(anchorView, com.google.android.material.R.attr.colorSurface),
            0.72f
        )
        return featured + other
    }
}
