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

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import at.saltyy.switchly.theme.AccentColor
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

// Keeps two-option segmented controls visually consistent across Switchly.
object SegmentedToggleUi {
    fun apply(
        context: Context,
        buttons: Iterable<MaterialButton>,
        selectedId: Int,
    ) {
        val accent = AccentColor.getAccentColorInt(context)
        val activeText = if (MaterialColors.isColorLight(accent)) Color.BLACK else Color.WHITE
        val strokeWidth = dp(context, 1)
        val cornerRadius = dp(context, 4)

        buttons.forEach { button ->
            val selected = button.id == selectedId
            button.minWidth = 0
            button.minimumWidth = 0
            val minimumHeight = dp(context, 48)
            button.minHeight = minimumHeight
            button.minimumHeight = minimumHeight
            button.insetTop = 0
            button.insetBottom = 0
            button.cornerRadius = cornerRadius
            button.shapeAppearanceModel = button.shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(cornerRadius.toFloat())
                .build()
            button.isActivated = selected
            button.backgroundTintList = ColorStateList.valueOf(if (selected) accent else Color.TRANSPARENT)
            button.strokeColor = ColorStateList.valueOf(accent)
            button.strokeWidth = strokeWidth
            button.setTextColor(if (selected) activeText else accent)
            button.iconTint = ColorStateList.valueOf(if (selected) activeText else accent)
            button.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x35))
            button.alpha = if (selected) 1f else 0.62f
            button.jumpDrawablesToCurrentState()
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
