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

package at.saltyy.switchly.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import java.util.WeakHashMap

/**
 * Small runtime polish pass shared by all accent themes.
 * The regular theme remains the source of truth.
 * This helper only normalizes UI states that are commonly recreated late by RecyclerView/Material widgets: switches/checkables, checked chips, info/status icons, badges and disabled alpha.
 * Button colors deliberately stay owned by Switchly button styles/CustomAccentApplier to avoid visible late text-color changes.
 * Semantic error/destructive colors are left alone.
 */
object UiConsistency {
    private val originalAlpha = WeakHashMap<View, Float>()

    fun apply(activity: Activity) {
        val root = activity.findViewById<View>(android.R.id.content) ?: return
        val run: () -> Unit = {
            runCatching { applyRecursive(root, activity) }
            Unit
        }
        run()
        longArrayOf(90L, 280L, 650L).forEach { delay -> root.postDelayed(run, delay) }
    }

    private fun applyRecursive(view: View, activity: Activity) {
        applyView(view, activity)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyRecursive(view.getChildAt(i), activity)
        }
    }

    private fun applyView(view: View, activity: Activity) {
        val accent = AccentColor.getAccentColorInt(activity)
        val onSurface = resolveColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val neutral = ColorUtils.setAlphaComponent(onSurface, 0x8F)

        val baseAlpha = originalAlpha.getOrPut(view) { view.alpha }
        if (!view.isEnabled) {
            view.alpha = minOf(baseAlpha, 0.55f)
        } else if (view.alpha <= 0.56f && baseAlpha > 0.56f) {
            view.alpha = baseAlpha
        }

        when (view) {
            is SwitchCompat -> CustomAccentApplier.tintSwitch(view)

            is CompoundButton -> if (view !is SwitchCompat) {
                val states = arrayOf(
                    intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
                    intArrayOf(android.R.attr.state_enabled),
                    intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_enabled),
                )
                val colors = intArrayOf(
                    accent,
                    neutral,
                    ColorUtils.setAlphaComponent(accent, 0x66),
                    ColorUtils.setAlphaComponent(neutral, 0x66),
                )
                view.buttonTintList = ColorStateList(states, colors)
            }

            is Chip -> if (view.isCheckable) {
                val baseBackground = view.chipBackgroundColor?.defaultColor
                    ?: resolveColor(activity, com.google.android.material.R.attr.colorSurfaceVariant, Color.TRANSPARENT)
                val states = arrayOf(
                    intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
                    intArrayOf(android.R.attr.state_enabled),
                    intArrayOf(-android.R.attr.state_enabled),
                )
                view.chipBackgroundColor = ColorStateList(
                    states,
                    intArrayOf(
                        ColorUtils.blendARGB(baseBackground, accent, 0.14f),
                        baseBackground,
                        ColorUtils.setAlphaComponent(baseBackground, 0x88),
                    ),
                )
                view.setTextColor(
                    ColorStateList(
                        states,
                        intArrayOf(accent, onSurface, ColorUtils.setAlphaComponent(onSurface, 0x66)),
                    ),
                )
                view.chipIconTint = ColorStateList.valueOf(accent)
                view.checkedIconTint = ColorStateList.valueOf(accent)
            }

            is MaterialCardView -> if (runCatching { view.isChecked }.getOrDefault(false)) {
                view.strokeColor = accent
                view.strokeWidth = maxOf(view.strokeWidth, dp(activity, 2))
            }

            is ImageView -> {
                val idName = resourceEntryName(view)
                val isInfoOrStatus = idName.contains("info", ignoreCase = true) ||
                    idName.contains("status", ignoreCase = true) ||
                    idName.contains("badge", ignoreCase = true)
                if (idName == "btnTestScheduleInfo") {
                    // The per-schedule action is an inspection affordance, not a highlighted state.
                    view.imageTintList = ColorStateList.valueOf(neutral)
                } else if (isInfoOrStatus && !hasToolbarParent(view)) {
                    view.imageTintList = ColorStateList.valueOf(accent)
                }
            }

            is TextView -> {
                val idName = resourceEntryName(view)
                if (idName == "tvActiveBadge") {
                    // Filled profile badge owns both its accent background and readable foreground.
                    val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE
                    view.setTextColor(onAccent)
                } else if (idName.contains("badge", ignoreCase = true) && view.isEnabled) {
                    view.setTextColor(accent)
                }
            }
        }

    }

    private fun hasToolbarParent(view: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent is MaterialToolbar) return true
            parent = parent.parent
        }
        return false
    }

    private fun resourceEntryName(view: View): String = runCatching {
        if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id)
    }.getOrDefault("")

    private fun resolveColor(activity: Activity, attr: Int, fallback: Int): Int =
        com.google.android.material.color.MaterialColors.getColor(activity, attr, fallback)

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
