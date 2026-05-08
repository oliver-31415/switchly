/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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
    private const val KEY_LAST_LOCATION_TRANSITION_PREFIX = "last_location_transition_" // + scheduleId + _enter/_exit -> ms
    private const val KEY_LOCATION_ARMED_PREFIX = "location_armed_" // + scheduleId -> bool

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

    fun getLastLocationTransitionMs(ctx: Context, scheduleId: Int, transitionKey: String): Long {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getLong(KEY_LAST_LOCATION_TRANSITION_PREFIX + scheduleId + "_" + transitionKey, 0L)
    }

    fun setLastLocationTransitionMs(ctx: Context, scheduleId: Int, transitionKey: String, value: Long) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putLong(KEY_LAST_LOCATION_TRANSITION_PREFIX + scheduleId + "_" + transitionKey, value) }
    }

    fun isLocationArmed(ctx: Context, scheduleId: Int): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_LOCATION_ARMED_PREFIX + scheduleId, false)
    }

    fun setLocationArmed(ctx: Context, scheduleId: Int, armed: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            if (armed) putBoolean(KEY_LOCATION_ARMED_PREFIX + scheduleId, true)
            else remove(KEY_LOCATION_ARMED_PREFIX + scheduleId)
        }
    }

    fun resetActiveScheduleState(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            putBoolean(KEY_ENABLED_BY_SCHEDULE, false)
            putBoolean(KEY_DISABLED_BY_SCHEDULE, false)
            putBoolean(KEY_HAD_ENABLE_AND_DISABLE, false)
            putBoolean(KEY_HAD_DISABLE_AND_ENABLE, false)
            putBoolean(KEY_MANUAL_OVERRIDE_ACTIVE, false)
            remove(KEY_MANUAL_OVERRIDE_SCHEDULE_ID)
            remove(KEY_ACTIVE_RANGE_SCHEDULE_ID)
            val keys = sp.all.keys.filter {
                it.startsWith(KEY_LAST_FIRED_PREFIX) ||
                    it.startsWith(KEY_LAST_LOCATION_TRANSITION_PREFIX) ||
                    it.startsWith(KEY_LOCATION_ARMED_PREFIX)
            }
            for (k in keys) remove(k)
        }
    }

}
