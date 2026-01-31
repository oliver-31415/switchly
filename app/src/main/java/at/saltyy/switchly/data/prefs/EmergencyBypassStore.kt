package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate
import java.time.ZoneId

object EmergencyBypassStore {
    private const val PREFS = "switchly_prefs"
    private const val KEY_UNTIL = "emergency_bypass_until"
    private const val KEY_LAST_USED_EPOCH_DAY = "emergency_last_used_epoch_day"
    private const val KEY_FEATURE_ENABLED = "emergency_bypass_feature_enabled"

    /** Global toggle: is Emergency Unlock feature enabled at all? */
    fun isFeatureEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // default = true to match current behavior
        return sp.getBoolean(KEY_FEATURE_ENABLED, true)
    }

    /** Enable / disable the Emergency Unlock feature. */
    fun setFeatureEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_FEATURE_ENABLED, enabled)
                if (!enabled) {
                    // Clear any active bypass when turning feature off
                    putLong(KEY_UNTIL, 0L)
                }
            }
    }

    /** True if user has already used emergency unlock today (local time). */
    fun hasUsedToday(context: Context): Boolean {
        // If feature is disabled, treat it as not usable at all
        if (!isFeatureEnabled(context)) return false

        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = sp.getLong(KEY_LAST_USED_EPOCH_DAY, Long.MIN_VALUE)
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        return last == today
    }

    /** True if user is allowed to use emergency unlock today (not used yet). */
    private fun canUseToday(context: Context): Boolean = isFeatureEnabled(context) && !hasUsedToday(context)

    private fun markUsedToday(context: Context) {
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putLong(KEY_LAST_USED_EPOCH_DAY, today) }
    }

    /**
     * Enable emergency bypass for [minutes], but only if not already used today and the feature is enabled.
     * Returns true when enabled; false if daily limit or feature toggle prevents it.
     */
    fun enableIfAllowed(context: Context, minutes: Int): Boolean {
        if (!isFeatureEnabled(context)) return false
        if (!canUseToday(context)) return false
        enable(context, minutes)
        markUsedToday(context)
        EmergencyUnlockCountStore.incrementToday(context)
        return true
    }

    /** Enable emergency bypass for [minutes] (no daily limit check). */
    fun enable(context: Context, minutes: Int) {
        if (!isFeatureEnabled(context)) return
        val until = System.currentTimeMillis() + minutes * 60_000L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putLong(KEY_UNTIL, until) }
    }

    /** True if we are within the bypass window. */
    fun isActive(context: Context): Boolean {
        if (!isFeatureEnabled(context)) return false
        val until = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_UNTIL, 0L)
        return until > System.currentTimeMillis()
    }

    /** Minutes remaining (rounded down). */
    fun minutesRemaining(context: Context): Int {
        if (!isFeatureEnabled(context)) return 0
        val until = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_UNTIL, 0L)
        val diff = until - System.currentTimeMillis()
        return if (diff > 0) (diff / 60_000L).toInt() else 0
    }
}
