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

    // Pause/extend support
    private const val KEY_PAUSED = "emergency_bypass_paused"
    private const val KEY_PAUSED_REMAINING_MS = "emergency_bypass_paused_remaining_ms"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Emergency Unlock is intended to reset per calendar day (local time).
     * Without this, users can pause yesterday's remaining time and resume it today while also getting today's fresh allowance (rollover).
     */
    private fun clearStateIfDayChanged(context: Context) {
        val sp = prefs(context)

        val hasAnyState = sp.getLong(KEY_UNTIL, 0L) > 0L ||
            sp.getBoolean(KEY_PAUSED, false) ||
            sp.getLong(KEY_PAUSED_REMAINING_MS, 0L) > 0L

        if (!hasAnyState) return

        val lastUsedDay = sp.getLong(KEY_LAST_USED_EPOCH_DAY, Long.MIN_VALUE)
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()

        if (lastUsedDay != today) {
            sp.edit {
                putLong(KEY_UNTIL, 0L)
                putBoolean(KEY_PAUSED, false)
                putLong(KEY_PAUSED_REMAINING_MS, 0L)
            }
        }
    }

    // Global toggle: is Emergency Unlock feature enabled at all?
    fun isFeatureEnabled(context: Context): Boolean {
        // default = true to match current behavior
        return prefs(context).getBoolean(KEY_FEATURE_ENABLED, true)
    }

    // Enable/disable the Emergency Unlock feature.
    fun setFeatureEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_FEATURE_ENABLED, enabled)
            if (!enabled) {
                // Clear any active/paused bypass when turning feature off
                putLong(KEY_UNTIL, 0L)
                putBoolean(KEY_PAUSED, false)
                putLong(KEY_PAUSED_REMAINING_MS, 0L)
            }
        }
    }

    // True if user has already used emergency unlock today (local time).
    fun hasUsedToday(context: Context): Boolean {
        // If feature is disabled, treat it as not usable at all
        if (!isFeatureEnabled(context)) return false

        val last = prefs(context).getLong(KEY_LAST_USED_EPOCH_DAY, Long.MIN_VALUE)
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        return last == today
    }

    // True if user is allowed to use emergency unlock today (not used yet).
    private fun canUseToday(context: Context): Boolean = isFeatureEnabled(context) && !hasUsedToday(context)

    private fun markUsedToday(context: Context) {
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        prefs(context).edit { putLong(KEY_LAST_USED_EPOCH_DAY, today) }
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

    // Enable emergency bypass for [minutes] (no daily limit check).
    fun enable(context: Context, minutes: Int) {
        if (!isFeatureEnabled(context)) return
        val safeMinutes = minutes.coerceAtLeast(1)
        val until = System.currentTimeMillis() + safeMinutes * 60_000L
        prefs(context).edit {
            putLong(KEY_UNTIL, until)
            putBoolean(KEY_PAUSED, false)
            putLong(KEY_PAUSED_REMAINING_MS, 0L)
        }
    }

    // End the emergency bypass immediately (active or paused).
    fun cancel(context: Context) {
        prefs(context).edit {
            putLong(KEY_UNTIL, 0L)
            putBoolean(KEY_PAUSED, false)
            putLong(KEY_PAUSED_REMAINING_MS, 0L)
        }
    }

    // True if we are within the bypass window.
    fun isActive(context: Context): Boolean {
        if (!isFeatureEnabled(context)) return false
        clearStateIfDayChanged(context)
        clearExpiredIfNeeded(context)
        if (isPaused(context)) return false
        val until = prefs(context).getLong(KEY_UNTIL, 0L)
        return until > System.currentTimeMillis()
    }

    // True if emergency bypass is paused with remaining time saved.
    fun isPaused(context: Context): Boolean {
        if (!isFeatureEnabled(context)) return false
        clearStateIfDayChanged(context)
        val sp = prefs(context)
        val paused = sp.getBoolean(KEY_PAUSED, false)
        if (!paused) return false

        val remaining = sp.getLong(KEY_PAUSED_REMAINING_MS, 0L)
        if (remaining <= 0L) {
            sp.edit {
                putBoolean(KEY_PAUSED, false)
                putLong(KEY_PAUSED_REMAINING_MS, 0L)
            }
            return false
        }
        return true
    }

    // Pause an active emergency bypass. Returns true if state changed.
    fun pause(context: Context): Boolean {
        if (!isActive(context)) return false
        val remaining = getRemainingMillis(context)
        if (remaining <= 0L) return false

        prefs(context).edit {
            putBoolean(KEY_PAUSED, true)
            putLong(KEY_PAUSED_REMAINING_MS, remaining)
            putLong(KEY_UNTIL, 0L)
        }
        return true
    }

    // Resume a paused emergency bypass. Returns true if state changed.
    fun resume(context: Context): Boolean {
        if (!isPaused(context)) return false
        val remaining = prefs(context).getLong(KEY_PAUSED_REMAINING_MS, 0L)
        if (remaining <= 0L) {
            cancel(context)
            return false
        }

        val until = System.currentTimeMillis() + remaining
        prefs(context).edit {
            putLong(KEY_UNTIL, until)
            putBoolean(KEY_PAUSED, false)
            putLong(KEY_PAUSED_REMAINING_MS, 0L)
        }
        return true
    }

    /**
     * Extend an active or paused emergency bypass by [minutes].
     * Returns true if extension was applied.
     */
    fun extend(context: Context, minutes: Int): Boolean {
        if (!isFeatureEnabled(context)) return false
        clearStateIfDayChanged(context)
        val extraMs = minutes.coerceAtLeast(1) * 60_000L
        val sp = prefs(context)

        return when {
            isPaused(context) -> {
                val current = sp.getLong(KEY_PAUSED_REMAINING_MS, 0L).coerceAtLeast(0L)
                sp.edit { putLong(KEY_PAUSED_REMAINING_MS, current + extraMs) }
                true
            }
            isActive(context) -> {
                val until = sp.getLong(KEY_UNTIL, 0L)
                sp.edit { putLong(KEY_UNTIL, until + extraMs) }
                true
            }
            else -> false
        }
    }

    // Remaining milliseconds (active or paused).
    fun getRemainingMillis(context: Context): Long {
        if (!isFeatureEnabled(context)) return 0L

        clearStateIfDayChanged(context)

        if (isPaused(context)) {
            return prefs(context).getLong(KEY_PAUSED_REMAINING_MS, 0L).coerceAtLeast(0L)
        }

        val until = prefs(context).getLong(KEY_UNTIL, 0L)
        val diff = until - System.currentTimeMillis()
        return diff.coerceAtLeast(0L)
    }

    // Minutes remaining (rounded up so sub-1min still shows as 1).
    fun minutesRemaining(context: Context): Int {
        val rem = getRemainingMillis(context)
        return if (rem > 0L) ((rem + 59_999L)/60_000L).toInt() else 0
    }

    private fun clearExpiredIfNeeded(context: Context) {
        val sp = prefs(context)
        val until = sp.getLong(KEY_UNTIL, 0L)
        if (until > 0L && until <= System.currentTimeMillis()) {
            sp.edit { putLong(KEY_UNTIL, 0L) }
        }
    }
}
