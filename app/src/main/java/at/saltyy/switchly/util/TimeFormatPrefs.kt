package at.saltyy.switchly.util

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object TimeFormatPrefs {
    const val KEY = "pref_time_format"
    private const val VALUE_SYSTEM = "system"
    private const val VALUE_24H = "24h"
    private const val VALUE_12H = "12h"

    fun getMode(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(KEY, VALUE_SYSTEM) ?: VALUE_SYSTEM
    }

    fun setMode(context: Context, mode: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY, mode)
        }
    }

    fun is24Hour(context: Context): Boolean {
        return when (getMode(context)) {
            VALUE_24H -> true
            VALUE_12H -> false
            else -> android.text.format.DateFormat.is24HourFormat(context)
        }
    }

    fun formatMinutesOfDay(context: Context, minutes: Int): String {
        val safe = minutes.coerceIn(0, 24 * 60 - 1)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, safe / 60)
            set(Calendar.MINUTE, safe % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val pattern = if (is24Hour(context)) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(cal.time)
    }
}
