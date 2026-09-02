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
        textSize = sp(12f)
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = sp(15f)
        isFakeBoldText = true
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

        // Foqos FourWeekHeatmapView: day-of-month labels run ABOVE each column —
        // one label per column (the oldest day in that column), never stacked.
        labelPaint.color = textColor
        val cal = Calendar.getInstance()
        for (i in dayValuesMs.indices) {
            val pos = gridPosition(i) ?: continue
            if (pos.second != 0) continue
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -(dayValuesMs.size - 1 - i))
            val dayLabel = cal.get(Calendar.DAY_OF_MONTH).toString()
            val x = paddingLeft + pos.first * (cellSize + gap) + cellSize / 2f
            canvas.drawText(dayLabel, x, paddingTop + sp(12f), labelPaint)
        }

        for (i in dayValuesMs.indices) {
            val pos = gridPosition(i) ?: continue
            val (col, row) = pos

            val left = paddingLeft + col * (cellSize + gap)
            val top = gridTop + row * (cellSize + gap)
            cellRect.set(left, top, left + cellSize, top + cellSize)

            val v = dayValuesMs[i]
            cellPaint.color = colorFor(v)
            val radius = cellSize * 0.26f
            canvas.drawRoundRect(cellRect, radius, radius, cellPaint)

            if (i == todayIndex || i == selectedDay) {
                cellStrokePaint.color = accent
                canvas.drawRoundRect(cellRect, radius, radius, cellStrokePaint)
            }

            // Day number renders INSIDE the cell when it has data or is selected.
            val hasValue = v > 0L
            if (hasValue || i == selectedDay) {
                cal.timeInMillis = System.currentTimeMillis()
                cal.add(Calendar.DAY_OF_YEAR, -(dayValuesMs.size - 1 - i))
                val label = cal.get(Calendar.DAY_OF_MONTH).toString()
                val onColor = onBucketColor(v)
                numberPaint.color = onColor
                val x = left + cellSize / 2f
                val y = top + cellSize / 2f - (numberPaint.descent() + numberPaint.ascent()) / 2f
                canvas.drawText(label, x, y, numberPaint)
            }
        }
    }

    /** Column/row for an index in the 28-day sequence: left->right, top->bottom. */
    private fun gridPosition(index: Int): Pair<Int, Int>? {
        if (index < 0 || index >= dayValuesMs.size) return null
        return (index % COLS) to (index / COLS)
    }

    private fun colorFor(valueMs: Long): Int {
        val bucket = bucketFor(valueMs)
        if (bucket < 0) {
            // Empty day: subtle text-color tint so it's visible in light AND dark mode.
            return (textColor and 0x00FFFFFF) or 0x1F000000
        }
        return bucketColors(accent)[bucket]
    }

    /** Number color inside a cell: dark text on light buckets, white on dark buckets. */
    private fun onBucketColor(valueMs: Long): Int {
        return if (bucketFor(valueMs) <= 1) Color.argb(0xFF, 0x1B, 0x1B, 0x18) else Color.WHITE
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
                // Same geometry as drawing: sequential grid.
                val idx = row * COLS + col
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

        /** Intensity bucket for a day value in ms (Foqos legend: <1h, 1-3h, 3-5h, >5h). */
        fun bucketFor(valueMs: Long): Int = when {
            valueMs <= 0L -> -1
            valueMs < 3_600_000L -> 0
            valueMs < 3 * 3_600_000L -> 1
            valueMs < 5 * 3_600_000L -> 2
            else -> 3
        }

        /**
         * The 4 bucket fill colors, light -> dark single-hue ramp derived from the
         * active accent (Foqos uses a light->dark purple ramp on its accent).
         */
        fun bucketColors(accent: Int): IntArray {
            val hsv = FloatArray(3)
            Color.colorToHSV(accent, hsv)
            fun variant(lighten: Float, satMul: Float): Int {
                val v = FloatArray(3)
                v[0] = hsv[0]
                v[1] = (hsv[1] * satMul).coerceIn(0.25f, 1f)
                v[2] = (hsv[2] + lighten).coerceIn(0f, 1f)
                return Color.HSVToColor(v)
            }
            return intArrayOf(
                variant(0.42f, 0.55f),
                variant(0.18f, 0.8f),
                accent,
                variant(-0.22f, 1.05f),
            )
        }

        /** Human-readable bucket labels for legends. */
        fun bucketLabels(): List<String> = listOf("<1h", "1-3h", "3-5h", ">5h")
    }
}
