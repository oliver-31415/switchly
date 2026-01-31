package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit

object TempAllowStore {
    private const val PREFS = "switchly_prefs"
    private const val KEY_PREFIX = "temp_allow_"

    fun allow(context: Context, pkg: String, durationMillis: Long) {
        val until = System.currentTimeMillis() + durationMillis
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putLong(KEY_PREFIX + pkg, until) }
    }

    fun isAllowed(context: Context, pkg: String): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = sp.getLong(KEY_PREFIX + pkg, 0L)
        if (until == 0L) return false
        if (System.currentTimeMillis() > until) {
            sp.edit { remove(KEY_PREFIX + pkg) }
            return false
        }
        return true
    }
}
