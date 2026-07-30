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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withSave
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class OnboardingUsageDonutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Segment(
        val fraction: Float,
        val color: Int,
        val icon: Drawable? = null,
        val label: String? = null,
        val percentageLabel: String? = null
    )

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val arcBounds = RectF()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(dp(1.5f), 0f, dp(0.5f), 0x66000000)
    }
    private var segments: List<Segment> = emptyList()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun submitSegments(value: List<Segment>) {
        segments = value.filter { it.fraction > 0.001f }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val requested = dp(252f).toInt()
        val width = resolveSize(requested, widthMeasureSpec)
        val height = resolveSize(requested, heightMeasureSpec)
        val size = min(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty()) return

        val size = min(width, height).toFloat()
        val strokeWidth = size * 0.24f
        val padding = strokeWidth / 2f + dp(8f)
        arcBounds.set(padding, padding, size - padding, size - padding)
        arcPaint.strokeWidth = strokeWidth

        val gap = if (segments.size > 1) 2.4f else 0f
        var start = -90f
        segments.forEach { segment ->
            val fullSweep = segment.fraction.coerceIn(0f, 1f) * 360f
            val sweep = (fullSweep - gap).coerceAtLeast(0.8f)
            arcPaint.color = segment.color
            canvas.drawArc(arcBounds, start + gap / 2f, sweep, false, arcPaint)

            if (fullSweep >= 7f) {
                drawSegmentContent(
                    canvas = canvas,
                    segment = segment,
                    angleDegrees = start + fullSweep / 2f,
                    size = size,
                    strokeWidth = strokeWidth,
                    fullSweep = fullSweep
                )
            }
            start += fullSweep
        }
    }

    private fun drawSegmentContent(
        canvas: Canvas,
        segment: Segment,
        angleDegrees: Float,
        size: Float,
        strokeWidth: Float,
        fullSweep: Float
    ) {
        val center = size / 2f
        val radius = (size / 2f) - (strokeWidth / 2f) - dp(8f)
        val angle = Math.toRadians(angleDegrees.toDouble())
        val cx = center + (cos(angle) * radius).toFloat()
        val cy = center + (sin(angle) * radius).toFloat()
        val contentColor = if (ColorUtils.calculateLuminance(segment.color) > 0.48) Color.BLACK else Color.WHITE

        if (segment.icon != null) {
            val percentage = segment.percentageLabel.orEmpty()
            if (fullSweep >= 28.8f) {
                val iconSize = min(dp(38f), strokeWidth * 0.72f)
                    .toInt()
                    .coerceAtLeast(dp(28f).toInt())
                val iconCenterY = cy - dp(6f)
                val left = (cx - iconSize / 2f).toInt()
                val top = (iconCenterY - iconSize / 2f).toInt()
                val icon = segment.icon.constantState
                    ?.newDrawable(resources)
                    ?.mutate()
                    ?: segment.icon.mutate()
                val previousBounds = icon.copyBounds()
                canvas.withSave {
                    icon.setBounds(left, top, left + iconSize, top + iconSize)
                    icon.draw(canvas)
                }
                icon.bounds = previousBounds

                if (percentage.isNotBlank()) {
                    drawTextLine(
                        canvas = canvas,
                        text = percentage,
                        x = cx,
                        baselineY = cy + dp(23f),
                        color = contentColor,
                        textSizeSp = 10.5f
                    )
                }
            } else if (percentage.isNotBlank()) {
                // Segments below 8% do not have enough room for both an icon and text without overlapping neighbouring segments, so show only the percentage.
                drawTextLine(
                    canvas = canvas,
                    text = percentage,
                    x = cx,
                    baselineY = cy + dp(4f),
                    color = contentColor,
                    textSizeSp = 8.5f
                )
            }
            return
        }

        val label = segment.label.orEmpty()
        val percentage = segment.percentageLabel.orEmpty()
        if (label.isBlank() && percentage.isBlank()) return

        if (label.isNotBlank() && fullSweep >= 11f) {
            drawTextLine(
                canvas = canvas,
                text = label,
                x = cx,
                baselineY = cy - dp(2f),
                color = contentColor,
                textSizeSp = when {
                    fullSweep < 16f -> 7.5f
                    label.length > 7 -> 8.5f
                    else -> 9.5f
                }
            )
            if (percentage.isNotBlank()) {
                drawTextLine(
                    canvas = canvas,
                    text = percentage,
                    x = cx,
                    baselineY = cy + dp(13f),
                    color = contentColor,
                    textSizeSp = if (fullSweep < 16f) 9f else 10.5f
                )
            }
        } else if (percentage.isNotBlank()) {
            drawTextLine(
                canvas = canvas,
                text = percentage,
                x = cx,
                baselineY = cy + dp(4f),
                color = contentColor,
                textSizeSp = 9.5f
            )
        }
    }

    private fun drawTextLine(
        canvas: Canvas,
        text: String,
        x: Float,
        baselineY: Float,
        color: Int,
        textSizeSp: Float
    ) {
        labelPaint.color = color
        labelPaint.textSize = sp(textSizeSp)
        canvas.drawText(text, x, baselineY, labelPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )
}
