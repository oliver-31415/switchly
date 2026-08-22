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

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.shape.MaterialShapeDrawable

object UsageDatePickerAccentTint {
    fun apply(activity: Activity, picker: MaterialDatePicker<*>) {
        if (!CustomAccentApplier.isCustomAccentEnabled(activity)) {
            return
        }

        fun install(attempt: Int) {
            val decor = picker.dialog?.window?.decorView
            if (decor == null) {
                if (attempt < 6) {
                    activity.window.decorView.postDelayed({ install(attempt + 1) }, 80L)
                }
                return
            }

            val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                runCatching { applyToRoot(activity, decor) }
            }
            runCatching { decor.viewTreeObserver.addOnGlobalLayoutListener(listener) }
            picker.addOnDismissListener {
                runCatching {
                    if (decor.viewTreeObserver.isAlive) {
                        decor.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                    }
                }
            }
            longArrayOf(0L, 80L, 180L, 360L, 720L, 1200L).forEach { delay ->
                decor.postDelayed({ runCatching { applyToRoot(activity, decor) } }, delay)
            }
        }

        activity.window.decorView.post { install(0) }
    }

    private fun applyToRoot(activity: Activity, root: View) {
        val accent = AccentColor.getAccentColorInt(activity)
        val defaultAccent = ContextCompat.getColor(activity, R.color.accent_default_green)
        val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.45) Color.BLACK else Color.WHITE
        val subtleAccent = ColorUtils.setAlphaComponent(accent, 0x30)

        fun sweep(view: View) {
            val idName = runCatching {
                if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else ""
            }.getOrDefault("")
            val className = view.javaClass.name
            val isRange = idName.contains("range", ignoreCase = true) ||
                idName.contains("selected", ignoreCase = true)

            retintDrawable(view.background, defaultAccent, accent, subtleAccent, isRange)
            ViewCompat.getBackgroundTintList(view)?.let { tint ->
                if (isRange || looksLikePickerAccent(tint.defaultColor, defaultAccent)) {
                    ViewCompat.setBackgroundTintList(
                        view,
                        ColorStateList.valueOf(if (isRange && !view.isSelected && !view.isActivated) subtleAccent else accent)
                    )
                }
            }

            if (idName.contains("confirm", ignoreCase = true) ||
                idName.contains("cancel", ignoreCase = true) ||
                className.contains("MaterialButton", ignoreCase = true)
            ) {
                styleActionButton(view, accent)
            } else if (view.isSelected || view.isActivated) {
                view.backgroundTintList = ColorStateList.valueOf(accent)
                (view as? TextView)?.setTextColor(onAccent)
            } else if (isRange) {
                view.backgroundTintList = ColorStateList.valueOf(subtleAccent)
            }

            if (view is TextView && view !is MaterialButton && looksLikePickerAccent(view.currentTextColor, defaultAccent)) {
                view.setTextColor(accent)
            }

            if (view is ViewGroup) {
                for (i in 0 until view.childCount) sweep(view.getChildAt(i))
            }
        }

        sweep(root)
    }

    private fun styleActionButton(view: View, accent: Int) {
        val text = view as? TextView ?: return
        text.setTextColor(accent)
        text.alpha = if (view.isEnabled) 1f else 0.48f

        if (view is MaterialButton) {
            view.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            view.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
            view.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x28))
            view.iconTint = ColorStateList.valueOf(accent)
        }
    }

    private fun retintDrawable(
        drawable: Drawable?,
        defaultAccent: Int,
        accent: Int,
        subtleAccent: Int,
        forceRangeTint: Boolean
    ) {
        drawable ?: return
        runCatching {
            val target = if (forceRangeTint) subtleAccent else accent
            when (drawable) {
                is ColorDrawable -> {
                    if (forceRangeTint || looksLikePickerAccent(drawable.color, defaultAccent)) drawable.color = target
                }
                is GradientDrawable -> {
                    val color = drawable.color?.defaultColor
                    if (forceRangeTint || (color != null && looksLikePickerAccent(color, defaultAccent))) drawable.setColor(target)
                }
                is MaterialShapeDrawable -> {
                    drawable.fillColor?.defaultColor?.let { color ->
                        if (forceRangeTint || looksLikePickerAccent(color, defaultAccent)) drawable.fillColor = ColorStateList.valueOf(target)
                    }
                    drawable.strokeColor?.defaultColor?.let { color ->
                        if (forceRangeTint || looksLikePickerAccent(color, defaultAccent)) drawable.strokeColor = ColorStateList.valueOf(accent)
                    }
                }
                is InsetDrawable -> retintDrawable(drawable.drawable, defaultAccent, accent, subtleAccent, forceRangeTint)
                is LayerDrawable -> {
                    for (i in 0 until drawable.numberOfLayers) {
                        retintDrawable(drawable.getDrawable(i), defaultAccent, accent, subtleAccent, forceRangeTint)
                    }
                }
                is RippleDrawable -> {
                    drawable.setColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x28)))
                    for (i in 0 until drawable.numberOfLayers) {
                        retintDrawable(drawable.getDrawable(i), defaultAccent, accent, subtleAccent, forceRangeTint)
                    }
                }
                is StateListDrawable -> {
                    val count = runCatching {
                        StateListDrawable::class.java.getMethod("getStateCount").invoke(drawable) as Int
                    }.getOrDefault(0)
                    val getter = runCatching {
                        StateListDrawable::class.java.getMethod("getStateDrawable", Int::class.javaPrimitiveType!!)
                    }.getOrNull()
                    if (getter != null) {
                        for (i in 0 until count) {
                            retintDrawable(
                                getter.invoke(drawable, i) as? Drawable,
                                defaultAccent,
                                accent,
                                subtleAccent,
                                forceRangeTint
                            )
                        }
                    } else {
                        DrawableCompat.setTint(DrawableCompat.wrap(drawable.mutate()), target)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun looksLikePickerAccent(color: Int, defaultAccent: Int): Boolean {
        if (Color.alpha(color) == 0) {
            return false
        }
        if (color == defaultAccent) {
            return true
        }
        val dr = kotlin.math.abs(Color.red(color) - Color.red(defaultAccent))
        val dg = kotlin.math.abs(Color.green(color) - Color.green(defaultAccent))
        val db = kotlin.math.abs(Color.blue(color) - Color.blue(defaultAccent))
        val closeToDefault = dr + dg + db < 128
        val greenish = Color.green(color) > Color.red(color) + 6 && Color.green(color) > Color.blue(color) + 6
        return closeToDefault || greenish
    }
}
