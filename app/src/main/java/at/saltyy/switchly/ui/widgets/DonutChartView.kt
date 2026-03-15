package at.saltyy.switchly.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import at.saltyy.switchly.R
import kotlin.math.min

class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    private var segments: List<Float> = emptyList()
    private var colors: List<Int> = listOf(
        ContextCompat.getColor(context, R.color.accent_blue),
        ContextCompat.getColor(context, R.color.accent_orange),
        ContextCompat.getColor(context, R.color.accent_purple)
    )

    fun setData(fractions: List<Float>) {
        segments = fractions.map { it.coerceAtLeast(0f) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = min(w, h)
        val stroke = size * 0.10f
        paint.strokeWidth = stroke

        val pad = stroke/2f + 4f
        rect.set((w - size)/2f + pad, (h - size)/2f + pad, (w + size)/2f - pad, (h + size)/2f - pad)

        // background ring
        paint.color = ContextCompat.getColor(context, android.R.color.darker_gray)
        paint.alpha = 60
        canvas.drawArc(rect, 0f, 360f, false, paint)
        paint.alpha = 255

        if (segments.isEmpty()) return

        var start = -90f
        for (i in segments.indices) {
            val sweep = 360f * segments[i].coerceAtMost(1f)
            if (sweep <= 0.5f) continue
            paint.color = colors[i % colors.size]
            canvas.drawArc(rect, start, sweep, false, paint)
            start += sweep
        }
    }
}
