package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import at.saltyy.switchly.blocking.BlockingRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Global Switchly state.
 * temp-enable is implemented as a real enable (KEY_ENABLED=true) temporarily, and then restored to the previous base state when the timer expires.
 * This avoids "UI shows enabled but blocking doesn't really run" edge-cases.
 */
object SwitchModeStore {

    private const val PREFS = "switchly_prefs"

    private const val KEY_ENABLED = "switch_mode_enabled"
    private const val KEY_TEMP_DISABLE_UNTIL = "switch_mode_temp_disable_until"
    private const val KEY_TEMP_ENABLE_UNTIL = "switch_mode_temp_enable_until"
    private const val KEY_REQUIRE_NFC_DISABLE = "switch_mode_require_nfc"

    // store previous base state so we can restore after temp-enable expires
    private const val KEY_BASE_BEFORE_TEMP_ENABLE = "switch_mode_base_before_temp_enable"

    private val _enabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(true)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow

    @Volatile
    private var initialized: Boolean = false

    fun ensureInit(ctx: Context) {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    _enabledFlow.value = isEnabled(ctx)
                    if (isEnabled(ctx)) {
                        BlockingRuntime.ensureRunning(ctx)
                    }
                    initialized = true
                }
            }
        }
    }

    fun isEnabled(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = sp.getBoolean(KEY_ENABLED, true)

        val tempDisableUntil = sp.getLong(KEY_TEMP_DISABLE_UNTIL, 0L)
        val tempEnableUntil = sp.getLong(KEY_TEMP_ENABLE_UNTIL, 0L)
        val now = System.currentTimeMillis()

        // temp-disable always wins
        if (tempDisableUntil != 0L && now < tempDisableUntil) return false

        // temp-enable overrides base=false
        if (tempEnableUntil != 0L && now < tempEnableUntil) return true

        return base
    }

    /**
     * Returns only the persisted base on/off flag, ignoring any temporary disable.
     */
    fun isBaseEnabled(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_ENABLED, true)
    }

    /**
     * Permanently enables or disables Switchly (user toggle).
     *
     * - When enabling, any temporary disable timestamp is cleared.
     * - When disabling, temp-disable data is left untouched (for UI display / history purposes).
     */
    fun setEnabled(ctx: Context, enabled: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            putBoolean(KEY_ENABLED, enabled)

            if (enabled) {
                putLong(KEY_TEMP_DISABLE_UNTIL, 0L)
            }

            // explicit on/off cancels temp-enable and its restore marker
            putLong(KEY_TEMP_ENABLE_UNTIL, 0L)
            remove(KEY_BASE_BEFORE_TEMP_ENABLE)
        }

        _enabledFlow.value = isEnabled(ctx)

        // Manually set -> not "enabled by schedule"
        ScheduleRuntimeStore.setEnabledBySchedule(ctx, false)

        // If a RANGE schedule is currently active and the user flips the state manually,
        // we treat this as a temporary "manual override" so the schedule doesn't instantly
        // re-assert its state on the next tick.
        if (ScheduleRuntimeStore.hadEnableAndDisable(ctx) || ScheduleRuntimeStore.hadDisableAndEnable(ctx)) {
            ScheduleRuntimeStore.setManualOverrideActive(ctx, true)
        }

        if (enabled) {
            BlockingRuntime.ensureRunning(ctx)
        } else {
            // Service can remain running but becomes idle by isEnabled() checks
        }
    }

    /**
     * Enable or disable Switchly as triggered by schedules.
     */
    fun setEnabledBySchedule(ctx: Context, enabled: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            putBoolean(KEY_ENABLED, enabled)

            // IMPORTANT:
            // Schedule-based enables must NOT cancel an active temporary disable window.
            // Otherwise a time/Wi-Fi schedule can accidentally "undo" a 10min QR/NFC temp-disable.
            // The temp-disable always wins in isEnabled(), so we keep it intact.

            putLong(KEY_TEMP_ENABLE_UNTIL, 0L)
            remove(KEY_BASE_BEFORE_TEMP_ENABLE)
        }

        _enabledFlow.value = isEnabled(ctx)
        ScheduleRuntimeStore.setEnabledBySchedule(ctx, enabled)

        if (enabled) {
            BlockingRuntime.ensureRunning(ctx)
        }
    }

    /**
     * Temporarily disables Switchly for the given duration in milliseconds.
     */
    fun setTemporarilyDisabled(ctx: Context, durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        sp.edit {
            // Do NOT touch KEY_ENABLED (base state). Temp disable is represented only by the "until" timestamp.
            putLong(KEY_TEMP_DISABLE_UNTIL, until)

            // mutually exclusive with temp-enable
            putLong(KEY_TEMP_ENABLE_UNTIL, 0L)
            remove(KEY_BASE_BEFORE_TEMP_ENABLE)
        }

        _enabledFlow.value = false

        BlockingRuntime.ensureRunning(ctx)
    }

    /**
     * Temp-enable implemented as:
     * - remember current base enabled flag
     * - set KEY_ENABLED=true (real enable)
     * - set temp-enable until
     * - clear temp-disable + any pending reenable from temp-disable
     */
    fun setTemporarilyEnabled(ctx: Context, durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val baseBefore = sp.getBoolean(KEY_ENABLED, true)

        sp.edit {
            // clear temp-disable
            putLong(KEY_TEMP_DISABLE_UNTIL, 0L)

            // remember base state and force-enable
            putBoolean(KEY_ENABLED, true)
            putBoolean(KEY_BASE_BEFORE_TEMP_ENABLE, baseBefore)

            // set temp-enable window
            putLong(KEY_TEMP_ENABLE_UNTIL, until)
        }
        _enabledFlow.value = true
        BlockingRuntime.ensureRunning(ctx)
    }

    fun getTemporaryRemainingMillis(ctx: Context): Long {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tempUntil = sp.getLong(KEY_TEMP_DISABLE_UNTIL, 0L)
        if (tempUntil == 0L) return 0L

        val remaining = tempUntil - System.currentTimeMillis()
        return if (remaining > 0L) remaining else 0L
    }

    fun getTemporaryEnableRemainingMillis(ctx: Context): Long {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = sp.getLong(KEY_TEMP_ENABLE_UNTIL, 0L)
        if (until == 0L) return 0L

        val remaining = until - System.currentTimeMillis()
        return if (remaining > 0L) remaining else 0L
    }

    /**
     * Called when temp-enable expires to restore the base enabled flag.
     */
    fun finishTemporaryEnableIfExpired(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = sp.getLong(KEY_TEMP_ENABLE_UNTIL, 0L)
        if (until == 0L) return

        val now = System.currentTimeMillis()
        if (now < until) return

        val baseBefore = sp.getBoolean(KEY_BASE_BEFORE_TEMP_ENABLE, true)

        sp.edit {
            putLong(KEY_TEMP_ENABLE_UNTIL, 0L)
            remove(KEY_BASE_BEFORE_TEMP_ENABLE)
            putBoolean(KEY_ENABLED, baseBefore)
        }

        _enabledFlow.value = isEnabled(ctx)
        if (isEnabled(ctx)) {
            BlockingRuntime.ensureRunning(ctx)
        }
    }

    /**
     * Clears an expired temp-disable window.
     */
    fun finishTemporaryDisableIfExpired(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = sp.getLong(KEY_TEMP_DISABLE_UNTIL, 0L)
        if (until == 0L) return

        val now = System.currentTimeMillis()
        if (now < until) return

        sp.edit { putLong(KEY_TEMP_DISABLE_UNTIL, 0L) }

        _enabledFlow.value = isEnabled(ctx)
        if (isEnabled(ctx)) {
            BlockingRuntime.ensureRunning(ctx)
        }
    }

    fun clearTemporary(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putLong(KEY_TEMP_DISABLE_UNTIL, 0L) }

        _enabledFlow.value = isEnabled(ctx)
        if (isEnabled(ctx)) {
            BlockingRuntime.ensureRunning(ctx)
        }
    }

    fun clearTemporaryEnable(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            putLong(KEY_TEMP_ENABLE_UNTIL, 0L)
            remove(KEY_BASE_BEFORE_TEMP_ENABLE)
        }

        _enabledFlow.value = isEnabled(ctx)

        if (isEnabled(ctx)) {
            BlockingRuntime.ensureRunning(ctx)
        }
    }

    /**
     * Returns whether NFC is required to disable Switchly.
     */
    fun isNfcRequiredForDisable(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_REQUIRE_NFC_DISABLE, false)
    }

    /**
     * Updates the "NFC required to disable" flag.
     *
     * Safety rule (improved):
     * - While Switchly is enabled, you may NOT turn this OFF (to avoid bypassing active lock).
     * - Turning it ON is always allowed.
     */
    fun setNfcRequiredForDisable(ctx: Context, required: Boolean) {
        val enabledNow = isEnabled(ctx)
        val emergencyActive = EmergencyBypassStore.isActive(ctx)

        // While Switchly is enabled, block attempts to disable the NFC requirement unless Emergency Bypass is active
        if (enabledNow && !required && !emergencyActive) {
            return
        }

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            putBoolean(KEY_REQUIRE_NFC_DISABLE, required)
        }
    }
}
