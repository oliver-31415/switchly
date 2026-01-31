package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * Persists whether the current profile was last applied by the Wi‑Fi trigger.
 *
 * This allows the Wi‑Fi receiver to **revert** the profile when Wi‑Fi disconnects
 * (or when no rule matches anymore) even if the app process was not running.
 */
object WifiTriggerStateStore {
    private const val PREFS = "switchly_wifi_trigger_state"
    private const val KEY_LAST_PROFILE = "last_profile"

    fun getLastAppliedProfile(context: Context): String? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(KEY_LAST_PROFILE, null)
    }

    fun setLastAppliedProfile(context: Context, profile: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putString(KEY_LAST_PROFILE, profile) }
    }

    fun clear(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { remove(KEY_LAST_PROFILE) }
    }
}
