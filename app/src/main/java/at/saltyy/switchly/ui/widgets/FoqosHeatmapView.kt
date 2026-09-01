/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the GNU General Public
 * License, or (at your option) any later version.
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
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import at.saltyy.switchly.theme.AccentColor
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

/**
 * Foqos-style 4-week focus heatmap (BlockedSessionsHabitTracker / FourWeekHeatmapView).
 *
 * Renders the last 28 days (4 rows x 7 columns, oldest first) as rounded cells whose
 * fill intensity encodes blocked-time for that day. Weekday initials run along the top,
 * tappable cells report the selected day index (0 = oldest, 27 = today).
 */
class FoqosHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Per-day values in ms, oldest -> today. Length should be DAYS. */
    private var dayValuesMs: LongArray = LongArray(DAYS)

    /** Index of today inside dayValuesMs (DAYS - 1). */
    private var todayIndex: Int = DAYS - 1

    private var selectedDay: Int = -1

    /** Delivered via [onDaySelected]; index into dayValuesMs, -1 when cleared. */
    var onDaySelected: ((Int) -> Unit)? = null

    private val accent: Int by lazy { AccentColor.getAccentColorInt(context) }
    private val textColor: Int by lazy {
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        tv.data
    }

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cellStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
    }

    private val cellRect = RectF()

    /** Grid geometry, computed in onSizeChanged. */
    private var gridTop = 0f
    private var cellSize = 0f
    private var gap = 0f

    init {
        // Monday-first column order like Foqos.
        setWillNotDraw(false)
        contentDescription = contentDescription ?: context.getString(
            at.saltyy.switchly.R.string.activity_heatmap_content_desc
        )
    }

    fun setData(valuesMs: LongArray, todayIdx: Int = valuesMs.size - 1) {
        dayValuesMs = if (valuesMs.size == DAYS) valuesMs else padOrTrim(valuesMs)
        todayIndex = todayIdx.coerceIn(0, dayValuesMs.size - 1)
        selectedDay = -1
        invalidate()
    }

    private fun padOrTrim(values: LongArray): LongArray {
        val out = LongArray(DAYS)
        val n = minOf(values.size, DAYS)
        if (n > 0) System.arraycopy(values, 0, out, DAYS - n, n)
        return out
    }

    fun clearSelection() {
        if (selectedDay != -1) {
            selectedDay = -1
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val labelHeight = sp(14f)
        gridTop = labelHeight + dp(4f)
        gap = dp(3f)
        val availW = w - paddingLeft - paddingRight
        val availH = h - paddingTop - paddingBottom - gridTop
        cellSize = max(
            0f,
            minOf((availW - gap * (COLS - 1)) / COLS, (availH - gap * (ROWS - 1)) / ROWS)
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(paddingLeft + paddingRight)
        val cell = (w - paddingLeft - paddingRight - dp(3f) * (COLS - 1)) / COLS
        val h = (sp(14f) + dp(4f) + ROWS * cell + (ROWS - 1) * dp(3f) + paddingTop + paddingBottom)
            .toInt()
            .coerceAtLeast(minHeightHint())
        setMeasuredDimension(
            w,
            resolveSize(h.toInt(), heightMeasureSpec)
        )
    }

    private fun minHeightHint(): Int = dp(120f).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Weekday initials (Mon..Sun across columns).
        val dfs = java.text.DateFormatSymbols.getInstance(Locale.getDefault())
        val weekdays = dfs.shortWeekdays // index 1..7 = Sun..Sat
        val order = intArrayOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        labelPaint.color = textColor
        for (c in 0 until COLS) {
            val label = weekdays[order[c]].take(1).uppercase(Locale.getDefault())
            val x = paddingLeft + c * (cellSize + gap) + cellSize / 2f
            canvas.drawText(label, x, paddingTop + sp(11f), labelPaint)
        }

        val maxVal = dayValuesMs.maxOrNull() ?: 0L

        // The calendar is aligned so that the column of "today" matches its weekday,
        // and rows walk backwards in 7-day steps.
        val todayCal = Calendar.getInstance()
        val todayDowMonFirst = ((todayCal.get(Calendar.DAY_OF_WEEK) + 5) % 7) // Mon=0..Sun=6
        val cal = Calendar.getInstance()

        for (i in dayValuesMs.indices) {
            val daysAgo = (dayValuesMs.size - 1) - i
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
            val col = (todayDowMonFirst - (daysAgo % 7) + 7) % 7
            val rowFromToday = daysAgo / 7
            val row = ROWS - 1 - rowFromToday
            if (row < 0 || col < 0) continue

            val left = paddingLeft + col * (cellSize + gap)
            val top = gridTop + row * (cellSize + gap)
            cellRect.set(left, top, left + cellSize, top + cellSize)

            val v = dayValuesMs[i]
            cellPaint.color = colorFor(v, maxVal)
            val radius = cellSize * 0.32f
            canvas.drawRoundRect(cellRect, radius, radius, cellPaint)

            if (i == todayIndex || i == selectedDay) {
                cellStrokePaint.color = accent
                canvas.drawRoundRect(cellRect, radius, radius, cellStrokePaint)
            }
        }
    }

    private fun colorFor(valueMs: Long, maxMs: Long): Int {
        if (valueMs <= 0L) {
            // Empty day: subtle text-color tint so it's visible in light AND dark mode.
            return (textColor and 0x00FFFFFF) or 0x16000000
        }
        val ratio = if (maxMs <= 0L) 1f else valueMs.toFloat() / maxMs
        // Scale alpha 35%..100% of the accent for a Foqos-like ramp.
        val alpha = (0x35 + (0xFF - 0x35) * ratio).toInt().coerceIn(0x35, 0xFF)
        return Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_UP -> {
                val x = event.x - paddingLeft
                val y = event.y - gridTop
                if (x < 0 || y < 0) return performClick()
                val col = (x / (cellSize + gap)).toInt()
                val row = (y / (cellSize + gap)).toInt()
                if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return performClick()
                // Invert the same geometry used when drawing.
                val todayDowMonFirst = ((Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7)
                val daysAgo = ((ROWS - 1 - row) * 7) + (todayDowMonFirst - col + 7) % 7
                val idx = (dayValuesMs.size - 1) - daysAgo
                if (idx in dayValuesMs.indices) {
                    selectedDay = if (selectedDay == idx) -1 else idx
                    invalidate()
                    onDaySelected?.invoke(selectedDay)
                    performClick()
                    return true
                }
                return performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    companion object {
        const val DAYS = 28
        const val ROWS = 4
        const val COLS = 7
    }
}
