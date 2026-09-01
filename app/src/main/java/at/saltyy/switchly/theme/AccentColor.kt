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

package at.saltyy.switchly.theme

import android.content.Context
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R

object AccentColor {

    private const val PREF_KEY = "pref_accent"
    private const val PREF_CUSTOM = "pref_accent_custom"

    enum class Option(val value: String) {
        GREEN("green"),
        BLUE("blue"),
        ORANGE("orange"),
        PURPLE("purple"),
        PINK("pink"),
        TEAL("teal"),
        RED("red"),
        AMBER("amber"),
        GRAY("gray"),
        CUSTOM("custom")
    }

    fun getOption(context: Context): Option {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return when (prefs.getString(PREF_KEY, "default")) {
            Option.BLUE.value   -> Option.BLUE
            Option.ORANGE.value -> Option.ORANGE
            Option.PURPLE.value -> Option.PURPLE
            Option.PINK.value   -> Option.PINK
            Option.TEAL.value   -> Option.TEAL
            Option.RED.value    -> Option.RED
            Option.AMBER.value  -> Option.AMBER
            Option.GRAY.value   -> Option.GRAY
            Option.CUSTOM.value -> Option.CUSTOM
            else                -> Option.GREEN
        }
    }

    fun getAccentColorInt(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return when (getOption(context)) {
            Option.GREEN  -> ContextCompat.getColor(context, R.color.accent_green)
            Option.BLUE   -> ContextCompat.getColor(context, R.color.accent_blue)
            Option.ORANGE -> ContextCompat.getColor(context, R.color.accent_orange)
            Option.PURPLE -> ContextCompat.getColor(context, R.color.accent_purple)
            Option.PINK   -> ContextCompat.getColor(context, R.color.accent_pink)
            Option.TEAL   -> ContextCompat.getColor(context, R.color.accent_teal)
            Option.RED    -> ContextCompat.getColor(context, R.color.accent_red)
            Option.AMBER  -> ContextCompat.getColor(context, R.color.accent_amber)
            Option.GRAY   -> ContextCompat.getColor(context, R.color.accent_gray)
            Option.CUSTOM -> {
                val hex = prefs.getString(PREF_CUSTOM, "#2E8B57") ?: "#2E8B57"
                try {
                    hex.toColorInt()
                } catch (e: IllegalArgumentException) {
                    ContextCompat.getColor(context, R.color.accent_green)
                }
            }
        }
    }

    // Foqos restyle: toolbars are flat surface (no accent header). The accent stays on
    // buttons/controls. All activities that tint their toolbar programmatically get the
    // surface color here, so the whole app flips consistently.
    fun getToolbarColor(context: Context): Int = ContextCompat.getColor(context, R.color.foqos_surface)

    fun getActiveColor(context: Context): ColorStateList = ColorStateList.valueOf(getAccentColorInt(context))
}
