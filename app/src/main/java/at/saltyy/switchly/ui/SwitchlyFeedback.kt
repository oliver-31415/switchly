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

import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import at.saltyy.switchly.theme.AccentColor
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar

/**
 * Screen actions use Snackbars; scanner/background events remain Toasts.
 * Keep every Snackbar visually neutral and use the current Switchly accent only for actions.
 */
fun Snackbar.applySwitchlyStyle(): Snackbar {
    val ctx = view.context
    val accent = AccentColor.getAccentColorInt(ctx)
    val surface = MaterialColors.getColor(
        ctx,
        com.google.android.material.R.attr.colorSurfaceVariant,
        Color.DKGRAY,
    )
    val onSurface = MaterialColors.getColor(
        ctx,
        com.google.android.material.R.attr.colorOnSurfaceVariant,
        Color.WHITE,
    )
    view.setBackgroundColor(surface)
    view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
        setTextColor(onSurface)
        maxLines = 3
    }
    setActionTextColor(accent)
    return this
}

fun View.showSwitchlyStatus(message: CharSequence, long: Boolean = false) {
    Snackbar.make(this, message, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
        .applySwitchlyStyle()
        .show()
}

fun View.showSwitchlyStatus(@StringRes messageRes: Int, long: Boolean = false) {
    showSwitchlyStatus(context.getString(messageRes), long)
}
