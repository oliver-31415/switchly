package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * Runtime helper for schedule behavior:
 * - tracks whether Switchly was enabled by a schedule (used for disable-on-exit)
 * - tracks whether Switchly was disabled by a schedule (used for enable-on-exit)
 * - tracks whether an ENABLE_AND_DISABLE/DISABLE_AND_ENABLE schedule was active
 * - stores "last fired token" per schedule id (debounce for single-time/connection one-shot actions)
 */
object ScheduleRuntimeStore {

    private const val PREFS = "switchly_schedule_runtime"

    private const val KEY_ENABLED_BY_SCHEDULE = "enabled_by_schedule"
    private const val KEY_DISABLED_BY_SCHEDULE = "disabled_by_schedule"

    private const val KEY_HAD_ENABLE_AND_DISABLE = "had_enable_and_disable"
    private const val KEY_HAD_DISABLE_AND_ENABLE = "had_disable_and_enable"

    // When the user manually changes the enabled state while a RANGE schedule is active, we mark a manual override so schedules won't immediately re-assert their state.
    // The override is cleared automatically once no schedule matches anymore.
    private const val KEY_MANUAL_OVERRIDE_ACTIVE = "manual_override_active"
    private const val KEY_MANUAL_OVERRIDE_SCHEDULE_ID = "manual_override_schedule_id"
    private const val KEY_ACTIVE_RANGE_SCHEDULE_ID = "active_range_schedule_id"
    private const val KEY_LAST_TICK_MS = "last_tick_ms"
    private const val KEY_LAST_EXECUTION_MS = "last_execution_ms"
    private const val KEY_LAST_DISABLE_BLOCKED_NFC_MS = "last_disable_blocked_nfc_ms"

    private const val KEY_LAST_FIRED_PREFIX = "last_fired_" // + scheduleId -> token

    fun wasEnabledBySchedule(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_ENABLED_BY_SCHEDULE, false)
    }

    fun setEnabledBySchedule(ctx: Context, value: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putBoolean(KEY_ENABLED_BY_SCHEDULE, value) }
    }

    fun wasDisabledBySchedule(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DISABLED_BY_SCHEDULE, false)
    }

    fun setDisabledBySchedule(ctx: Context, value: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putBoolean(KEY_DISABLED_BY_SCHEDULE, value) }
    }

    fun hadEnableAndDisable(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_HAD_ENABLE_AND_DISABLE, false)
    }

    fun setHadEnableAndDisable(ctx: Context, value: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putBoolean(KEY_HAD_ENABLE_AND_DISABLE, value) }
    }

    fun hadDisableAndEnable(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_HAD_DISABLE_AND_ENABLE, false)
    }

    fun setHadDisableAndEnable(ctx: Context, value: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putBoolean(KEY_HAD_DISABLE_AND_ENABLE, value) }
    }

    fun isManualOverrideActive(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_MANUAL_OVERRIDE_ACTIVE, false)
    }

    fun setManualOverrideActive(ctx: Context, value: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putBoolean(KEY_MANUAL_OVERRIDE_ACTIVE, value) }
    }


    fun getManualOverrideScheduleId(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getInt(KEY_MANUAL_OVERRIDE_SCHEDULE_ID, -1)
    }

    fun setManualOverrideScheduleId(ctx: Context, scheduleId: Int) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putInt(KEY_MANUAL_OVERRIDE_SCHEDULE_ID, scheduleId) }
    }

    fun clearManualOverrideScheduleId(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { remove(KEY_MANUAL_OVERRIDE_SCHEDULE_ID) }
    }

    fun getActiveRangeScheduleId(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getInt(KEY_ACTIVE_RANGE_SCHEDULE_ID, -1)
    }

    fun setActiveRangeScheduleId(ctx: Context, scheduleId: Int) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putInt(KEY_ACTIVE_RANGE_SCHEDULE_ID, scheduleId) }
    }

    fun clearActiveRangeScheduleId(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { remove(KEY_ACTIVE_RANGE_SCHEDULE_ID) }
    }

    fun getLastFiredToken(ctx: Context, scheduleId: Int): String? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(KEY_LAST_FIRED_PREFIX + scheduleId, null)?.takeIf { it.isNotBlank() }
    }

    fun setLastFiredToken(ctx: Context, scheduleId: Int, token: String) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putString(KEY_LAST_FIRED_PREFIX + scheduleId, token) }
    }

    fun clearLastFiredToken(ctx: Context, scheduleId: Int) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { remove(KEY_LAST_FIRED_PREFIX + scheduleId) }
    }

    fun markTickNow(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putLong(KEY_LAST_TICK_MS, System.currentTimeMillis()) }
    }

    fun getLastTickMs(ctx: Context): Long {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getLong(KEY_LAST_TICK_MS, 0L)
    }

    fun markExecutedNow(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putLong(KEY_LAST_EXECUTION_MS, System.currentTimeMillis()) }
    }

    fun getLastExecutionMs(ctx: Context): Long {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getLong(KEY_LAST_EXECUTION_MS, 0L)
    }

    fun markDisableBlockedByNfc(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putLong(KEY_LAST_DISABLE_BLOCKED_NFC_MS, System.currentTimeMillis()) }
    }

    fun getLastDisableBlockedByNfcMs(ctx: Context): Long {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getLong(KEY_LAST_DISABLE_BLOCKED_NFC_MS, 0L)
    }

    fun clearDisableBlockedByNfc(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { remove(KEY_LAST_DISABLE_BLOCKED_NFC_MS) }
    }

}
