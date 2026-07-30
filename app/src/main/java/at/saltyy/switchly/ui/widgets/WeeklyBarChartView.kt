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

package at.saltyy.switchly.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

class WeeklyBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var labelColorOverride: Int? = null
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
        color = Color.WHITE
        alpha = 235
    }

    private val valuePaintAbove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics)
        alpha = 220
    }

    private var values: List<Long> = emptyList()
    private var showWeekdayLabels: Boolean = false
    private var weekdayLabels: List<String> = buildLast7Weekdays()

    fun interface OnBarSelectedListener {
        fun onSelected(index: Int, valueMs: Long)
    }

    private var onBarSelectedListener: OnBarSelectedListener? = null
    private var selectedIndex: Int = -1

    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        alpha = 180
    }

    fun setOnBarSelectedListener(l: OnBarSelectedListener?) {
        onBarSelectedListener = l
        isClickable = l != null
    }

    fun setValues(ms: List<Long>) {
        values = ms
        invalidate()
    }

    fun setShowWeekdayLabels(show: Boolean) {
        if (showWeekdayLabels == show) {
            return
        }
        showWeekdayLabels = show
        invalidate()
    }

    fun setWeekdayLabels(labels: List<String>) {
        weekdayLabels = labels.take(7)
        invalidate()
    }

    fun setLabelColorOverride(color: Int?) {
        labelColorOverride = color
        invalidate()
    }

    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Prefer a Material "onSurface" color, but allow an explicit override for onboarding cards.
        dayPaint.color = labelColorOverride ?: resolveColor(com.google.android.material.R.attr.colorOnSurface)
        dayPaint.alpha = 220
        valuePaintAbove.color = dayPaint.color
        highlightPaint.color = dayPaint.color

        if (values.isEmpty()) {
            return
        }

        val maxV = max(1L, values.maxOrNull() ?: 1L).toFloat()
        val bars = values.size.coerceAtLeast(1)
        val gap = w * 0.02f
        val barW = (w - gap * (bars + 1))/bars

        val chartTop = 14f
        val weekdayArea = if (showWeekdayLabels) 22f else 0f
        val chartBottom = (h - weekdayArea - 6f).coerceAtLeast(chartTop + 8f)
        val radius = 10f

        bgPaint.color = ContextCompat.getColor(context, android.R.color.darker_gray)
        bgPaint.alpha = 70
        barPaint.color = AccentColor.getAccentColorInt(context)

        val fm = valuePaint.fontMetrics
        val textHeight = fm.descent - fm.ascent

        for (i in 0 until bars) {
            val left = gap + i * (barW + gap)
            val right = left + barW
            val frac = (values[i].toFloat()/maxV).coerceIn(0f, 1f)
            val barTop = chartBottom - ((chartBottom - chartTop) * frac)

            canvas.drawRoundRect(left, chartTop, right, chartBottom, radius, radius, bgPaint)
            canvas.drawRoundRect(left, barTop, right, chartBottom, radius, radius, barPaint)

            if (i == selectedIndex) {
                canvas.drawRoundRect(left, barTop, right, chartBottom, radius, radius, highlightPaint)
            }

            // Value label: prefer inside the bar near the bottom for readability.
            val valueLabel = formatCompactDuration(values[i])
            val preferredInsideY = chartBottom - 10f
            val minInsideY = barTop + textHeight + 2f
            val valueY = if (minInsideY <= preferredInsideY) {
                preferredInsideY
            } else {
                // Tiny bars: place label just above the bar so it stays readable.
                (barTop - 6f).coerceAtLeast(chartTop + textHeight)
            }
            val p = if (valueY < barTop) valuePaintAbove else valuePaint
            canvas.drawText(valueLabel, (left + right)/2f, valueY, p)

            if (showWeekdayLabels) {
                val day = weekdayLabels.getOrNull(i).orEmpty()
                val dayY = h - 2f
                canvas.drawText(day, (left + right)/2f, dayY, dayPaint)
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (values.isEmpty() || onBarSelectedListener == null) {
            return super.onTouchEvent(event)
        }

        val w = width.toFloat().takeIf { it > 0f } ?: return true
        val bars = values.size.coerceAtLeast(1)
        val gap = w * 0.02f
        val barW = (w - gap * (bars + 1))/bars
        val dx = barW + gap

        val x = event.x
        val idx = ((x - gap)/dx).toInt().coerceIn(0, bars - 1)

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)

                if (idx != selectedIndex) {
                    selectedIndex = idx
                    invalidate()
                }
                return true
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                val dxMove = abs(event.x - downX)
                val dyMove = abs(event.y - downY)
                if (!isDragging && (dxMove > touchSlop || dyMove > touchSlop)) {
                    isDragging = true
                    if (dyMove > dxMove * 1.2f) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        if (selectedIndex != -1) {
                            selectedIndex = -1
                            invalidate()
                        }
                    }
                }
                return true
            }

            android.view.MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    if (idx != selectedIndex) {
                        selectedIndex = idx
                        invalidate()
                    }
                    onBarSelectedListener?.onSelected(idx, values[idx])
                    performClick()
                }
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            android.view.MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun buildLast7Weekdays(): List<String> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(6)
        }
        val dfs = DateFormatSymbols.getInstance()
        return buildList {
            repeat(7) {
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                add(dfs.shortWeekdays.getOrNull(dow).orEmpty().trim())
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    private fun formatCompactDuration(ms: Long): String {
        if (ms <= 0L) {
            return "0m"
        }

        // Keep labels compact and unambiguous.
        // - < 60 min: "37m"
        // - >= 60 min: "3:38" (hours:minutes, rounded to nearest minute)
        val totalMinutes = ((ms + 30_000L)/60_000L).coerceAtLeast(0L)
        if (totalMinutes <= 0L) {
            return "<1m"
        }

        return if (totalMinutes >= 60L) {
            val hours = totalMinutes/60L
            val minutes = totalMinutes % 60L
            if (minutes == 0L) {
                "${hours}h"
            } else {
                String.format(java.util.Locale.getDefault(), "%d:%02d", hours, minutes)
            }
        } else {
            "${totalMinutes}m"
        }
    }
}
