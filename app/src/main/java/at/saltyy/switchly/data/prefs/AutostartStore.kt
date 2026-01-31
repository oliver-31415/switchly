package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit

object AutostartStore {

    private const val PREFS = "switchly_autostart"
    private const val KEY_AUTOSTART = "autostart_enabled"

    // default is true -> autostart enabled
    fun isEnabled(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_AUTOSTART, true)
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putBoolean(KEY_AUTOSTART, enabled) }
    }
}
