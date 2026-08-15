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
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.feature.widget.ActiveTimerWidgetProvider
import at.saltyy.switchly.util.ManagedDevicePolicyHelper
import at.saltyy.switchly.util.PersistentStatusNotifier
import at.saltyy.switchly.util.getLongCompat
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
    private const val KEY_LIMIT_SESSION_GENERATION = "switch_mode_limit_session_generation"

    // store previous base state so we can restore after temp-enable expires
    private const val KEY_BASE_BEFORE_TEMP_ENABLE = "switch_mode_base_before_temp_enable"
    private const val KEY_PROFILE_BEFORE_TEMP_ENABLE = "switch_mode_profile_before_temp_enable"

    private val _enabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow

    @Volatile
    private var initialized: Boolean = false

    // Marker for runtime-only per-session limit counters.
    fun getLimitSessionGeneration(ctx: Context): Long {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getLongCompat(KEY_LIMIT_SESSION_GENERATION, 0L)
    }

    private fun bumpLimitSessionGeneration(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = sp.getLongCompat(KEY_LIMIT_SESSION_GENERATION, 0L) + 1L
        sp.edit { putLong(KEY_LIMIT_SESSION_GENERATION, next) }
    }

    private fun recordEffectiveStateChange(
        ctx: Context,
        enabledBefore: Boolean,
        enabledAfter: Boolean,
        causedBySchedule: Boolean = false
    ) {
        if (enabledBefore == enabledAfter) {
            return
        }

        val action = if (enabledAfter) {
            SwitchlyActionCountStore.Action.ENABLE
        } else {
            SwitchlyActionCountStore.Action.DISABLE
        }
        SwitchlyActionCountStore.incrementToday(ctx, action)

        if (causedBySchedule) {
            val scheduleAction = if (enabledAfter) {
                SwitchlyActionCountStore.Action.SCHEDULE_ENABLE
            } else {
                SwitchlyActionCountStore.Action.SCHEDULE_DISABLE
            }
            SwitchlyActionCountStore.incrementToday(ctx, scheduleAction)
        }
    }

    private fun syncActiveSinceForEffectiveState(ctx: Context, enabledNow: Boolean = isEnabled(ctx)) {
        ActiveDurationStore.syncEffectiveState(ctx, enabledNow)
    }

    fun getActiveSinceMillis(ctx: Context): Long {
        syncActiveSinceForEffectiveState(ctx)
        return ActiveDurationStore.getActiveSinceMillis(ctx)
    }

    fun getActiveDurationMillis(ctx: Context): Long {
        syncActiveSinceForEffectiveState(ctx)
        return if (isEnabled(ctx)) {
            ActiveDurationStore.getActiveDurationMillis(ctx)
        } else {
            0L
        }
    }

    fun ensureInit(ctx: Context) {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    _enabledFlow.value = isEnabled(ctx)
                    ActiveTimerWidgetProvider.updateAll(ctx)
                    PersistentStatusNotifier.refresh(ctx)
                    syncActiveSinceForEffectiveState(ctx)
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
        val base = sp.getBoolean(KEY_ENABLED, false)

        val tempDisableUntil = sp.getLongCompat(KEY_TEMP_DISABLE_UNTIL, 0L)
        val tempEnableUntil = sp.getLongCompat(KEY_TEMP_ENABLE_UNTIL, 0L)
        val now = System.currentTimeMillis()

        // temp-disable always wins
        if (tempDisableUntil != 0L && now < tempDisableUntil) {
            return false
        }

        // temp-enable overrides base=false
        if (tempEnableUntil != 0L && now < tempEnableUntil) {
            return true
        }

        return base
    }

    // Returns only the persisted base on/off flag, ignoring any temporary disable.
    fun hasActiveTemporaryOverride(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val tempDisableUntil = sp.getLongCompat(KEY_TEMP_DISABLE_UNTIL, 0L)
        val tempEnableUntil = sp.getLongCompat(KEY_TEMP_ENABLE_UNTIL, 0L)
        return (tempDisableUntil != 0L && now < tempDisableUntil) ||
            (tempEnableUntil != 0L && now < tempEnableUntil)
    }

    fun hasActiveTemporaryEnable(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = sp.getLongCompat(KEY_TEMP_ENABLE_UNTIL, 0L)
        return until != 0L && System.currentTimeMillis() < until
    }

    fun setTemporaryEnableRestoreProfileFromSchedule(ctx: Context, profile: String?) {
        val cleanProfile = profile?.trim().orEmpty()
        if (cleanProfile.isBlank() || !hasActiveTemporaryEnable(ctx)) {
            return
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_PROFILE_BEFORE_TEMP_ENABLE, cleanProfile)
        }
        AppLogStore.append(ctx, "Schedule", "Queued profile restore after temporary enable id=$cleanProfile")
    }

    fun isBaseEnabled(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_ENABLED, false)
    }

    /**
     * Permanently enables or disables Switchly (user toggle).
     * - Any temporary disable timestamp is cleared, because a real manual on/off should win.
     * - Active temp-enable state is cancelled and its previous profile is restored.
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
        val now = System.currentTimeMillis()
        val hadActiveTempEnable = sp.getLongCompat(KEY_TEMP_ENABLE_UNTIL, 0L) > now
        val profileBeforeTempEnable = if (hadActiveTempEnable) sp.getString(KEY_PROFILE_BEFORE_TEMP_ENABLE, null) else null

        sp.edit {
            putBoolean(KEY_ENABLED, enabled)
            putLong(KEY_TEMP_DISABLE_UNTIL, 0L)

            // explicit on/off cancels temp-enable and its restore markers
            putLong(KEY_TEMP_ENABLE_UNTIL, 0L)
            remove(KEY_BASE_BEFORE_TEMP_ENABLE)
            remove(KEY_PROFILE_BEFORE_TEMP_ENABLE)
        }

        val effectiveAfter = isEnabled(ctx)
        if (currentlyEnabled != effectiveAfter) {
            bumpLimitSessionGeneration(ctx)
            recordEffectiveStateChange(ctx, currentlyEnabled, effectiveAfter)
        }

        if (!profileBeforeTempEnable.isNullOrBlank()) {
            ProfileStore.setCurrent(ctx, profileBeforeTempEnable)
            AppLogStore.append(ctx, "Profiles", "Restored previous profile id=$profileBeforeTempEnable")
        }

        _enabledFlow.value = effectiveAfter
        ActiveTimerWidgetProvider.updateAll(ctx)
        PersistentStatusNotifier.refresh(ctx)
        syncActiveSinceForEffectiveState(ctx, effectiveAfter)

        val rangeScheduleActive = ScheduleRuntimeStore.hadEnableAndDisable(ctx) || ScheduleRuntimeStore.hadDisableAndEnable(ctx)
        val activeRangeScheduleId = ScheduleRuntimeStore.getActiveRangeScheduleId(ctx)
        ScheduleRuntimeStore.setManualSchedulePauseActive(ctx, !enabled && rangeScheduleActive, activeRangeScheduleId)

        // If a RANGE schedule is currently active and the user flips the state manually, mark a temporary manual override so the schedule won't instantly fight the user.
        // IMPORTANT: keep schedule ownership flags intact while inside the active range, otherwise exit-revert at range end can break (e.g. NFC toggle at lunch keeps the profile enabled forever after end time).
        if (rangeScheduleActive) {
            ScheduleRuntimeStore.setManualOverrideActive(ctx, true)
            if (activeRangeScheduleId > 0) {
                ScheduleRuntimeStore.setManualOverrideScheduleId(ctx, activeRangeScheduleId)
            } else {
                // Recovery path: avoid keeping a sticky override without an owner.
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
            BlockingRuntime.stop(ctx)
        }
        ManagedDevicePolicyHelper.syncSelfUninstallBlock(ctx)

        return true
    }

    // Enable or disable Switchly as triggered by schedules.
    fun setEnabledBySchedule(ctx: Context, enabled: Boolean) {
        val effectiveBefore = isEnabled(ctx)
        val activeTempEnable = hasActiveTemporaryEnable(ctx)

        if (!AutomationModeStore.isScheduleAllowed(ctx)) {
            return
        }

        // Hard rule:
        // NFC lock ON  -> schedules cannot disable Switchly, but they may still enable it.
        // NFC lock OFF -> schedules can change state normally.
        if (!enabled && isNfcDisableLockEnforced(ctx) && effectiveBefore != enabled) {
            ScheduleRuntimeStore.markDisableBlockedByNfc(ctx)
            return
        }

        // A schedule-driven state change can clear previous warning markers.
        if (effectiveBefore != enabled) {
            ScheduleRuntimeStore.clearDisableBlockedByNfc(ctx)
        }

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            putBoolean(KEY_ENABLED, enabled)

            if (activeTempEnable) {
                // Do not cancel an active Temporary Enable/Profile window.
                // Schedules may update the base state underneath it, but the temporary profile/action must still expire normally.
                putBoolean(KEY_BASE_BEFORE_TEMP_ENABLE, enabled)
            } else {
                putLong(KEY_TEMP_ENABLE_UNTIL, 0L)
                remove(KEY_BASE_BEFORE_TEMP_ENABLE)
                remove(KEY_PROFILE_BEFORE_TEMP_ENABLE)
            }
        }

        val effectiveAfter = isEnabled(ctx)
        if (effectiveBefore != effectiveAfter) {
            bumpLimitSessionGeneration(ctx)
            recordEffectiveStateChange(
                ctx = ctx,
                enabledBefore = effectiveBefore,
                enabledAfter = effectiveAfter,
                causedBySchedule = true
            )
        }

        _enabledFlow.value = effectiveAfter
        ActiveTimerWidgetProvider.updateAll(ctx)
        PersistentStatusNotifier.refresh(ctx)
        syncActiveSinceForEffectiveState(ctx, effectiveAfter)
        ScheduleRuntimeStore.setEnabledBySchedule(ctx, enabled)

        if (effectiveAfter) {
            BlockingRuntime.ensureRunning(ctx)
        } else {
            BlockingRuntime.stop(ctx)
        }
        ManagedDevicePolicyHelper.syncSelfUninstallBlock(ctx)
    }

    // Temporarily disables Switchly for the given duration in milliseconds.
    fun setTemporarilyDisabled(ctx: Context, durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val baseEnabled = sp.getBoolean(KEY_ENABLED, false)
        val tempEnableActive = sp.getLongCompat(KEY_TEMP_ENABLE_UNTIL, 0L) > now
        val effectivelyEnabledBefore = isEnabled(ctx)

        if (!baseEnabled && !tempEnableActive) {
            AppLogStore.append(ctx, "Profiles", "Temp disable skipped reason=already_disabled")
            return
        }

        sp.edit {
            // IMPORTANT:
            // Do NOT change the persisted base on/off flag here.
            // Temp-disable is modeled as an override window (KEY_TEMP_DISABLE_UNTIL) in isEnabled().
            // If we flipped KEY_ENABLED=false, Switchly would stay disabled after the timer expires.
            putLong(KEY_TEMP_DISABLE_UNTIL, until)

            // Do NOT clear temp-enable here.
            // Temp-disable has priority in isEnabled(), so it can temporarily pause/override an active temp-enable window.
            // When temp-disable expires, the remaining temp-enable window should resume instead of becoming a permanent enable.
        }

        val effectivelyEnabledAfter = isEnabled(ctx)
        if (effectivelyEnabledBefore != effectivelyEnabledAfter) {
            bumpLimitSessionGeneration(ctx)
            recordEffectiveStateChange(ctx, effectivelyEnabledBefore, effectivelyEnabledAfter)
        }

        _enabledFlow.value = effectivelyEnabledAfter
        ActiveTimerWidgetProvider.updateAll(ctx)
        PersistentStatusNotifier.refresh(ctx)
        syncActiveSinceForEffectiveState(ctx)
        AppLogStore.append(ctx, "Profiles", "Temp disable started duration=${durationMs}ms")

        // Clear any currently visible blocker UI/state while the temporary disable window is active.
        BlockingRuntime.stop(ctx)
        ManagedDevicePolicyHelper.syncSelfUninstallBlock(ctx)
    }

    /**
     * Temp-enable implemented as:
     * - remember current base enabled flag
     * - set KEY_ENABLED=true (real enable)
     * - set temp-enable until
     * - clear temp-disable + any pending reenable from temp-disable
     */
    fun setTemporarilyEnabled(
        ctx: Context,
        durationMs: Long,
        previousProfileOverride: String? = null,
        targetProfileForLog: String? = null
    ) {
        val until = System.currentTimeMillis() + durationMs
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val now = System.currentTimeMillis()
        val activeTempUntil = sp.getLongCompat(KEY_TEMP_ENABLE_UNTIL, 0L)
        val effectivelyEnabledNow = isEnabled(ctx)

        // Preserve the original restore state while a Temporary Enable/Profile window is already active.
        // Re-scanning the same NFC/QR/barcode action should extend/refresh the timer, not turn the restore state into permanently enabled just because the temporary window is currently active.
        val baseBefore = if (activeTempUntil > now && sp.contains(KEY_BASE_BEFORE_TEMP_ENABLE)) {
            sp.getBoolean(KEY_BASE_BEFORE_TEMP_ENABLE, sp.getBoolean(KEY_ENABLED, false))
        } else if (effectivelyEnabledNow) {
            // If Switchly was genuinely enabled before starting Temporary Enable, expiry should keep it enabled.
            true
        } else {
            sp.getBoolean(KEY_ENABLED, false)
        }

        // Same rule for profile-scoped temp-enable: keep the ORIGINAL profile so expiry restores the last non-temporary profile.
        // If a temp-enable window is already active, the stored restore profile must win over a new override from a repeated scan.
        val profileBefore = if (activeTempUntil > now && sp.contains(KEY_PROFILE_BEFORE_TEMP_ENABLE)) {
            sp.getString(KEY_PROFILE_BEFORE_TEMP_ENABLE, ProfileStore.getCurrent(ctx))
        } else {
            previousProfileOverride ?: ProfileStore.getCurrent(ctx)
        }

        sp.edit {
            // clear temp-disable
            putLong(KEY_TEMP_DISABLE_UNTIL, 0L)

            // remember base state and force-enable
            putBoolean(KEY_ENABLED, true)
            putBoolean(KEY_BASE_BEFORE_TEMP_ENABLE, baseBefore)
            if (profileBefore != null) {
                putString(KEY_PROFILE_BEFORE_TEMP_ENABLE, profileBefore)
            } else {
                remove(KEY_PROFILE_BEFORE_TEMP_ENABLE)
            }

            // set temp-enable window
            putLong(KEY_TEMP_ENABLE_UNTIL, until)
        }
        val effectivelyEnabledAfter = isEnabled(ctx)
        if (effectivelyEnabledNow != effectivelyEnabledAfter) {
            bumpLimitSessionGeneration(ctx)
            recordEffectiveStateChange(ctx, effectivelyEnabledNow, effectivelyEnabledAfter)
        }

        _enabledFlow.value = effectivelyEnabledAfter
        ActiveTimerWidgetProvider.updateAll(ctx)
        PersistentStatusNotifier.refresh(ctx)
        syncActiveSinceForEffectiveState(ctx)
        val loggedTargetProfile = targetProfileForLog ?: ProfileStore.getCurrent(ctx) ?: "-"
        AppLogStore.append(ctx, "Profiles", "Temp enable started profile=$loggedTargetProfile duration=${durationMs}ms restoreEnabled=$baseBefore")
        AppLogStore.append(ctx, "Profiles", "Stored previous profile id=${profileBefore ?: "-"}")
        BlockingRuntime.ensureRunning(ctx)
        ManagedDevicePolicyHelper.syncSelfUninstallBlock(ctx)
    }

    fun getTemporaryRemainingMillis(ctx: Context): Long {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tempUntil = sp.getLongCompat(KEY_TEMP_DISABLE_UNTIL, 0L)
        if (tempUntil == 0L) {
            return 0L
        }

        val remaining = tempUntil - System.currentTimeMillis()
        return if (remaining > 0L) {
            remaining
        } else {
            0L
        }
    }

    fun getTemporaryEnableRemainingMillis(ctx: Context): Long {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = sp.getLongCompat(KEY_TEMP_ENABLE_UNTIL, 0L)
        if (until == 0L) {
            return 0L
        }

        val remaining = until - System.currentTimeMillis()
        return if (remaining > 0L) {
            remaining
        } else {
            0L
        }
    }

    // Called when temp-enable expires to restore the base enabled flag.
    fun finishTemporaryEnableIfExpired(ctx: Context, recordStats: Boolean = true) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = sp.getLongCompat(KEY_TEMP_ENABLE_UNTIL, 0L)
        if (until == 0L) {
            return
        }

        val now = System.currentTimeMillis()
        if (now < until) {
            return
        }

        val tempEnableWasMasked = sp.getLongCompat(KEY_TEMP_DISABLE_UNTIL, 0L) != 0L
        val effectivelyEnabledBefore = true
        val baseBefore = sp.getBoolean(KEY_BASE_BEFORE_TEMP_ENABLE, false)
        val profileBefore = sp.getString(KEY_PROFILE_BEFORE_TEMP_ENABLE, null)

        sp.edit {
            putLong(KEY_TEMP_ENABLE_UNTIL, 0L)
            remove(KEY_BASE_BEFORE_TEMP_ENABLE)
            remove(KEY_PROFILE_BEFORE_TEMP_ENABLE)
            putBoolean(KEY_ENABLED, baseBefore)
        }

        if (!profileBefore.isNullOrBlank()) {
            ProfileStore.setCurrent(ctx, profileBefore)
            AppLogStore.append(ctx, "Profiles", "Restored previous profile id=$profileBefore")
        } else {
            AppLogStore.append(ctx, "Profiles", "Restore skipped reason=no_previous_profile")
        }

        val effectivelyEnabledAfter = isEnabled(ctx)
        if (recordStats && !tempEnableWasMasked && effectivelyEnabledBefore != effectivelyEnabledAfter) {
            bumpLimitSessionGeneration(ctx)
            recordEffectiveStateChange(ctx, effectivelyEnabledBefore, effectivelyEnabledAfter)
        }

        AppLogStore.append(ctx, "Profiles", "Temp enable expired")
        _enabledFlow.value = effectivelyEnabledAfter
        ActiveTimerWidgetProvider.updateAll(ctx)
        PersistentStatusNotifier.refresh(ctx)
        syncActiveSinceForEffectiveState(ctx, effectivelyEnabledAfter)
        if (effectivelyEnabledAfter) {
            BlockingRuntime.ensureRunning(ctx)
        } else {
            BlockingRuntime.stop(ctx)
        }
        ManagedDevicePolicyHelper.syncSelfUninstallBlock(ctx)
    }

    // Clears an expired temp-disable window.
    fun finishTemporaryDisableIfExpired(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = sp.getLongCompat(KEY_TEMP_DISABLE_UNTIL, 0L)
        if (until == 0L) {
            return
        }

        val now = System.currentTimeMillis()
        if (now < until) {
            return
        }

        // The override was effective immediately before its expiry, even though isEnabled() already treats an expired timestamp as inactive at the exact moment this cleanup runs.
        val effectivelyEnabledBefore = false
        sp.edit { putLong(KEY_TEMP_DISABLE_UNTIL, 0L) }

        // If temp-disable was stacked on top of temp-enable, the temp-enable marker may have expired while temp-disable was still winning.
        // Reconcile that before deciding the final effective state, otherwise KEY_ENABLED=true from temp-enable could look like a permanent enable.
        finishTemporaryEnableIfExpired(ctx, recordStats = false)

        val effectivelyEnabledAfter = isEnabled(ctx)
        if (effectivelyEnabledBefore != effectivelyEnabledAfter) {
            bumpLimitSessionGeneration(ctx)
            recordEffectiveStateChange(ctx, effectivelyEnabledBefore, effectivelyEnabledAfter)
        }

        AppLogStore.append(ctx, "Profiles", "Temp disable expired")
        _enabledFlow.value = effectivelyEnabledAfter
        ActiveTimerWidgetProvider.updateAll(ctx)
        PersistentStatusNotifier.refresh(ctx)
        syncActiveSinceForEffectiveState(ctx)
        if (effectivelyEnabledAfter) {
            BlockingRuntime.ensureRunning(ctx)
        } else {
            BlockingRuntime.stop(ctx)
        }
        ManagedDevicePolicyHelper.syncSelfUninstallBlock(ctx)
    }

    fun clearTemporary(ctx: Context) {
        val effectivelyEnabledBefore = isEnabled(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putLong(KEY_TEMP_DISABLE_UNTIL, 0L) }

        val effectivelyEnabledAfter = isEnabled(ctx)
        if (effectivelyEnabledBefore != effectivelyEnabledAfter) {
            bumpLimitSessionGeneration(ctx)
            recordEffectiveStateChange(ctx, effectivelyEnabledBefore, effectivelyEnabledAfter)
        }
        _enabledFlow.value = effectivelyEnabledAfter
        ActiveTimerWidgetProvider.updateAll(ctx)
        PersistentStatusNotifier.refresh(ctx)
        syncActiveSinceForEffectiveState(ctx, effectivelyEnabledAfter)
        if (effectivelyEnabledAfter) {
            BlockingRuntime.ensureRunning(ctx)
        }
        ManagedDevicePolicyHelper.syncSelfUninstallBlock(ctx)
    }

    fun clearTemporaryEnable(ctx: Context) {
        val effectivelyEnabledBefore = isEnabled(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            putLong(KEY_TEMP_ENABLE_UNTIL, 0L)
            remove(KEY_BASE_BEFORE_TEMP_ENABLE)
            remove(KEY_PROFILE_BEFORE_TEMP_ENABLE)
        }

        val effectivelyEnabledAfter = isEnabled(ctx)
        if (effectivelyEnabledBefore != effectivelyEnabledAfter) {
            bumpLimitSessionGeneration(ctx)
            recordEffectiveStateChange(ctx, effectivelyEnabledBefore, effectivelyEnabledAfter)
        }
        _enabledFlow.value = effectivelyEnabledAfter
        ActiveTimerWidgetProvider.updateAll(ctx)
        PersistentStatusNotifier.refresh(ctx)
        syncActiveSinceForEffectiveState(ctx, effectivelyEnabledAfter)

        if (effectivelyEnabledAfter) {
            BlockingRuntime.ensureRunning(ctx)
        }
        ManagedDevicePolicyHelper.syncSelfUninstallBlock(ctx)
    }

    // Cancels an active temporary disable window and re-enables Switchly immediately.
    fun cancelTemporaryDisable(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = sp.getLongCompat(KEY_TEMP_DISABLE_UNTIL, 0L) > 0L
        if (!active) {
            return
        }

        val effectivelyEnabledBefore = isEnabled(ctx)
        sp.edit {
            putLong(KEY_TEMP_DISABLE_UNTIL, 0L)
        }
        val effectivelyEnabledAfter = isEnabled(ctx)
        if (effectivelyEnabledBefore != effectivelyEnabledAfter) {
            bumpLimitSessionGeneration(ctx)
            recordEffectiveStateChange(ctx, effectivelyEnabledBefore, effectivelyEnabledAfter)
        }

        // TempReenableStore.clear(ctx)
        _enabledFlow.value = effectivelyEnabledAfter
        ActiveTimerWidgetProvider.updateAll(ctx)
        PersistentStatusNotifier.refresh(ctx)
        syncActiveSinceForEffectiveState(ctx)
        BlockingRuntime.ensureRunning(ctx)
        ManagedDevicePolicyHelper.syncSelfUninstallBlock(ctx)
    }

    /**
     * Clears an expired temp-disable window if it's past due.
     * This is mainly for hygiene so we don't keep stale timestamps forever.
     */
    fun cancelTemporaryEnable(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = sp.getLongCompat(KEY_TEMP_ENABLE_UNTIL, 0L) > 0L
        if (!active) {
            return
        }

        val effectivelyEnabledBefore = isEnabled(ctx)
        val baseBefore = sp.getBoolean(KEY_BASE_BEFORE_TEMP_ENABLE, false)
        val profileBefore = sp.getString(KEY_PROFILE_BEFORE_TEMP_ENABLE, null)

        sp.edit {
            putLong(KEY_TEMP_ENABLE_UNTIL, 0L)
            remove(KEY_BASE_BEFORE_TEMP_ENABLE)
            remove(KEY_PROFILE_BEFORE_TEMP_ENABLE)
            putBoolean(KEY_ENABLED, baseBefore)
        }

        if (!profileBefore.isNullOrBlank()) {
            ProfileStore.setCurrent(ctx, profileBefore)
        }

        val effectivelyEnabledAfter = isEnabled(ctx)
        if (effectivelyEnabledBefore != effectivelyEnabledAfter) {
            bumpLimitSessionGeneration(ctx)
            recordEffectiveStateChange(ctx, effectivelyEnabledBefore, effectivelyEnabledAfter)
        }
        _enabledFlow.value = effectivelyEnabledAfter
        ActiveTimerWidgetProvider.updateAll(ctx)
        PersistentStatusNotifier.refresh(ctx)
        syncActiveSinceForEffectiveState(ctx, effectivelyEnabledAfter)
        if (effectivelyEnabledAfter) {
            BlockingRuntime.ensureRunning(ctx)
        }
        ManagedDevicePolicyHelper.syncSelfUninstallBlock(ctx)
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
