package at.saltyy.switchly.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ViewConfiguration
import android.view.View
import at.saltyy.switchly.theme.AccentColor
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Lightweight time-series chart for variable-length usage histories.
 * - Works well for 7/30/365 day series or 12-month aggregates
 * - Uses the current accent color (incl. custom colors)
 * - Intentionally keeps visuals minimal (no per-point labels) to avoid clutter on long series
 */
class TimeSeriesLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 55
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        alpha = 38
    }

    private var values: List<Long> = emptyList()

    // Optional X-axis labels (same size as values). Only a subset is drawn to avoid clutter.
    private var xLabels: List<String> = emptyList()

    // Gesture handling: distinguish a tap from a scroll/drag so charts inside scroll views feel sane.
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false

    fun interface OnPointSelectedListener {
        fun onSelected(index: Int, valueMs: Long)
    }

    private var onPointSelectedListener: OnPointSelectedListener? = null
    private var selectedIndex: Int = -1
    private val linePath = Path()
    private val fillPath = Path()
    private var maxValueMs: Long = 1L

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        alpha = 185
    }

    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = 10f * resources.displayMetrics.scaledDensity
        alpha = 170
    }

    fun setOnPointSelectedListener(l: OnPointSelectedListener?) {
        onPointSelectedListener = l
        isClickable = l != null
    }


    fun setValues(ms: List<Long>) {
        values = ms
        maxValueMs = (values.maxOrNull() ?: 1L).coerceAtLeast(1L)
        invalidate()
    }

    fun setXAxisLabels(labels: List<String>) {
        xLabels = labels
        invalidate()
    }

    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (values.isEmpty() || onPointSelectedListener == null) return super.onTouchEvent(event)

        val w = width.toFloat().takeIf { it > 0f } ?: return true
        val left = 10f * resources.displayMetrics.density
        val right = w - left
        val n = values.size
        if (n <= 0) return true

        val idx = if (n == 1) 0 else {
            val dx = (right - left)/(n - 1).toFloat()
            kotlin.math.round(((event.x - left)/dx)).toInt().coerceIn(0, n - 1)
        }

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
                    // Any meaningful movement means the user is dragging; don't show a modal popup.
                    isDragging = true

                    // Vertical scroll should go to the parent.
                    if (dyMove > dxMove * 1.2f) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        // Clear selection highlight while scrolling.
                        if (selectedIndex != -1) {
                            selectedIndex = -1
                            invalidate()
                        }
                    }
                }
                return true
            }

            android.view.MotionEvent.ACTION_UP -> {
                // Only treat as a "tap" if the user didn't scroll/drag.
                if (!isDragging) {
                    if (idx != selectedIndex) {
                        selectedIndex = idx
                        invalidate()
                    }
                    onPointSelectedListener?.onSelected(idx, values[idx])
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        val left = 10f * resources.displayMetrics.density
        val right = w - left
        val top = 10f * resources.displayMetrics.density

        val labelArea = if (xLabels.isNotEmpty()) (18f * resources.displayMetrics.scaledDensity) else 0f
        val bottom = (h - top - labelArea).coerceAtLeast(top + 12f * resources.displayMetrics.density)

        val accent = AccentColor.getAccentColorInt(context)
        linePaint.color = accent
        fillPaint.color = accent
        gridPaint.color = resolveColor(com.google.android.material.R.attr.colorOnSurface)

        // Subtle horizontal grid lines (25/50/75%)
        val gridSteps = 3
        for (i in 1..gridSteps) {
            val y = top + (bottom - top) * (i.toFloat()/(gridSteps + 1).toFloat())
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        val maxV = maxValueMs.toFloat().coerceAtLeast(1f)
        val n = values.size
        if (n == 1) {
            // Single point: draw a short line so it still looks like "something happened"
            val y = bottom - (bottom - top) * (values[0].toFloat()/maxV).coerceIn(0f, 1f)
            canvas.drawLine(left, y, right, y, linePaint)
            return
        }

        val dx = (right - left)/(n - 1).toFloat()

        linePath.reset()
        for (i in 0 until n) {
            val x = left + dx * i.toFloat()
            val frac = (values[i].toFloat()/maxV).coerceIn(0f, 1f)
            val y = bottom - (bottom - top) * frac
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }

        fillPath.reset()
        fillPath.set(linePath)
        fillPath.lineTo(right, bottom)
        fillPath.lineTo(left, bottom)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // X-axis labels (minimal): show a few evenly spaced labels + always show first/last.
        if (xLabels.isNotEmpty() && xLabels.size == n) {
            axisTextPaint.color = resolveColor(com.google.android.material.R.attr.colorOnSurface)

            val maxLabels = 6
            val step = maxOf(1, ceil(n.toDouble()/maxLabels.toDouble()).toInt())
            val indices = linkedSetOf<Int>().apply {
                add(0)
                add(n - 1)
                var i = 0
                while (i < n) {
                    add(i)
                    i += step
                }
            }.toList().sorted()

            val y = h - 4f * resources.displayMetrics.density
            for (i in indices) {
                val x = left + dx * i.toFloat()
                val label = xLabels[i]
                if (label.isNotBlank()) canvas.drawText(label, x, y, axisTextPaint)
            }
        }

        if (selectedIndex >= 0 && selectedIndex < n) {
            val dx = (right - left)/(n - 1).toFloat()
            val x = left + dx * selectedIndex.toFloat()
            val frac = (values[selectedIndex].toFloat()/maxV).coerceIn(0f, 1f)
            val y = bottom - (bottom - top) * frac

            markerPaint.color = linePaint.color
            markerStrokePaint.color = resolveColor(com.google.android.material.R.attr.colorOnSurface)

            val r = 4.6f * resources.displayMetrics.density
            canvas.drawCircle(x, y, r * 1.6f, markerStrokePaint)
            canvas.drawCircle(x, y, r, markerPaint)
        }

    }
}

