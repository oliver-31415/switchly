package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import at.saltyy.switchly.blocking.BlockingRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Global Switchly state.
 * Temp-enable is implemented as a real enable (KEY_ENABLED=true) temporarily, and then restored to the previous base state when the timer expires.
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

    // Returns only the persisted base on/off flag, ignoring any temporary disable.
    fun hasActiveTemporaryOverride(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val tempDisableUntil = sp.getLong(KEY_TEMP_DISABLE_UNTIL, 0L)
        val tempEnableUntil = sp.getLong(KEY_TEMP_ENABLE_UNTIL, 0L)
        return (tempDisableUntil != 0L && now < tempDisableUntil) ||
            (tempEnableUntil != 0L && now < tempEnableUntil)
    }

    fun isBaseEnabled(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_ENABLED, true)
    }

    /**
     * Permanently enables or disables Switchly (user toggle).
     * - When enabling, any temporary disable timestamp is cleared.
     * - When disabling, temp-disable data is left untouched (for UI display/history purposes).
     */
    fun setEnabled(ctx: Context, enabled: Boolean) {
        setEnabled(ctx, enabled, allowNfcBypass = false)
    }

    /**
     * Enables/disables Switchly.
     * Security: if Switchly is currently enabled and "require NFC to disable" is enabled, then calls that try to disable Switchly will be ignored unless [allowNfcBypass] is true.
     */
    fun setEnabled(ctx: Context, enabled: Boolean, allowNfcBypass: Boolean): Boolean {
        val currentlyEnabled = isEnabled(ctx)
        if (!enabled && currentlyEnabled && isNfcDisableLockEnforced(ctx) && !allowNfcBypass) {
            // Block non-NFC disabling paths (e.g. schedules, shortcuts, UI toggles).
            return false
        }

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

        val rangeScheduleActive = ScheduleRuntimeStore.hadEnableAndDisable(ctx) || ScheduleRuntimeStore.hadDisableAndEnable(ctx)

        // If a RANGE schedule is currently active and the user flips the state manually, mark a temporary manual override so the schedule won't instantly fight the user.
        // IMPORTANT: keep schedule ownership flags intact while inside the active range, otherwise exit-revert at range end can break (e.g. NFC toggle at lunch keeps the profile enabled forever after end time).
        if (rangeScheduleActive) {
            ScheduleRuntimeStore.setManualOverrideActive(ctx, true)
            val activeRangeScheduleId = ScheduleRuntimeStore.getActiveRangeScheduleId(ctx)
            if (activeRangeScheduleId > 0) {
                ScheduleRuntimeStore.setManualOverrideScheduleId(ctx, activeRangeScheduleId)
            } else {
                // Legacy / recovery path: avoid keeping a sticky override without an owner.
                ScheduleRuntimeStore.clearManualOverrideScheduleId(ctx)
            }
        } else {
            // Outside an active range, manual toggles should clear schedule ownership markers.
            ScheduleRuntimeStore.setManualOverrideActive(ctx, false)
            ScheduleRuntimeStore.clearManualOverrideScheduleId(ctx)
            ScheduleRuntimeStore.clearActiveRangeScheduleId(ctx)
            ScheduleRuntimeStore.setEnabledBySchedule(ctx, false)
            ScheduleRuntimeStore.setDisabledBySchedule(ctx, false)
        }

        if (enabled) {
            BlockingRuntime.ensureRunning(ctx)
        } else {
            // Service can remain running but becomes idle by isEnabled() checks
        }

        return true
    }

    // Enable or disable Switchly as triggered by schedules.
    fun setEnabledBySchedule(ctx: Context, enabled: Boolean) {
        val current = isEnabled(ctx)

        if (!AutomationModeStore.isScheduleAllowed(ctx)) {
            return
        }

        // Hard rule:
        // NFC lock ON  -> schedules cannot disable Switchly, but they may still enable it.
        // NFC lock OFF -> schedules can change state normally.
        if (!enabled && isNfcDisableLockEnforced(ctx) && current != enabled) {
            ScheduleRuntimeStore.markDisableBlockedByNfc(ctx)
            return
        }

        // A schedule-driven state change can clear previous warning markers.
        if (current != enabled) {
            ScheduleRuntimeStore.clearDisableBlockedByNfc(ctx)
        }

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            putBoolean(KEY_ENABLED, enabled)
            putLong(KEY_TEMP_ENABLE_UNTIL, 0L)
            remove(KEY_BASE_BEFORE_TEMP_ENABLE)
        }

        _enabledFlow.value = isEnabled(ctx)
        ScheduleRuntimeStore.setEnabledBySchedule(ctx, enabled)

        if (enabled) {
            BlockingRuntime.ensureRunning(ctx)
        }
    }

    // Temporarily disables Switchly for the given duration in milliseconds.
    fun setTemporarilyDisabled(ctx: Context, durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        sp.edit {
            // IMPORTANT:
            // Do NOT change the persisted base on/off flag here.
            // Temp-disable is modeled as an override window (KEY_TEMP_DISABLE_UNTIL) in isEnabled().
            // If we flipped KEY_ENABLED=false, Switchly would stay disabled after the timer expires.
            putLong(KEY_TEMP_DISABLE_UNTIL, until)

            // mutually exclusive with temp-enable
            putLong(KEY_TEMP_ENABLE_UNTIL, 0L)
            remove(KEY_BASE_BEFORE_TEMP_ENABLE)
        }

        _enabledFlow.value = isEnabled(ctx)

        // Keep runtime alive so our services can continue ticking and enforce schedules/limits.
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

        val now = System.currentTimeMillis()
        val activeTempUntil = sp.getLong(KEY_TEMP_ENABLE_UNTIL, 0L)

        // Important when user adjusts an already running temp-enable timer: keep the ORIGINAL base state so expiry restores correctly.
        // Otherwise KEY_ENABLED is already forced to true and we would "learn" the wrong base.
        val baseBefore = if (activeTempUntil > now && sp.contains(KEY_BASE_BEFORE_TEMP_ENABLE)) {
            sp.getBoolean(KEY_BASE_BEFORE_TEMP_ENABLE, sp.getBoolean(KEY_ENABLED, true))
        } else {
            sp.getBoolean(KEY_ENABLED, true)
        }

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

    // Called when temp-enable expires to restore the base enabled flag.
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

    // Clears an expired temp-disable window.
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

    // Cancels an active temporary disable window and re-enables Switchly immediately.
    fun cancelTemporaryDisable(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = sp.getLong(KEY_TEMP_DISABLE_UNTIL, 0L) > 0L
        if (!active) return

        sp.edit {
            putLong(KEY_TEMP_DISABLE_UNTIL, 0L)
        }

        // TempReenableStore.clear(ctx)
        _enabledFlow.value = isEnabled(ctx)
        BlockingRuntime.ensureRunning(ctx)
    }

    /**
     * Clears an expired temp-disable window if it's past due.
     * This is mainly for hygiene so we don't keep stale timestamps forever.
     */
    fun cancelTemporaryEnable(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = sp.getLong(KEY_TEMP_ENABLE_UNTIL, 0L) > 0L
        if (!active) return

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

    fun isNfcDisableLockEnforced(ctx: Context): Boolean {
        return isNfcRequiredForDisable(ctx) && AutomationModeStore.isNfcExclusiveControlActive(ctx)
    }

    // Returns whether NFC is required to disable Switchly.
    fun isNfcRequiredForDisable(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_REQUIRE_NFC_DISABLE, false)
    }

    /**
     * Updates the "NFC required to disable" flag.
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
