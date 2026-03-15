package at.saltyy.switchly.theme

import android.content.Context
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import androidx.core.graphics.toColorInt

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

    fun getToolbarColor(context: Context): Int = getAccentColorInt(context)

    fun getActiveColor(context: Context): ColorStateList = ColorStateList.valueOf(getAccentColorInt(context))
}
