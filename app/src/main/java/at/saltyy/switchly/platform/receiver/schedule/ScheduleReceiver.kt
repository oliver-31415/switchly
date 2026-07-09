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

package at.saltyy.switchly.platform.receiver.schedule

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.PauseUntilStore
import at.saltyy.switchly.data.prefs.ScheduleExecutionCountStore
import at.saltyy.switchly.data.prefs.ScheduleInsights
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.ScheduleRuntimeStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import com.google.android.gms.location.Geofence
import java.util.Calendar
import kotlin.math.abs

class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TICK) return
        val ctx = context.applicationContext

        // Heartbeat for schedule health diagnostics in UI.
        ScheduleRuntimeStore.markTickNow(ctx)

        if (!AutomationModeStore.isScheduleAllowed(ctx)) {
            // Schedule automation channel is currently disabled by control mode.
            return
        }

        val locationScheduleId = intent.getIntExtra(EXTRA_LOCATION_SCHEDULE_ID, -1)
        val locationTransition = intent.getIntExtra(EXTRA_LOCATION_TRANSITION, -1)
        if (locationScheduleId > 0 && locationTransition > 0) {
            handleLocationTransition(ctx, locationScheduleId, locationTransition)
            return
        }

        val wifiReason = intent.getStringExtra("wifi_reason")
        val alarmReason = intent.getStringExtra("alarm_reason")
        val isTimeBoundaryTick = alarmReason == "time_boundary" || alarmReason == "fallback_repeat"

        // Bluetooth events forwarded by BluetoothConnectionReceiver
        val eventBtName = intent.getStringExtra("eventBtName")
        val eventBtAddr = intent.getStringExtra("eventBtAddr")
        val hasBtStateEvent = intent.hasExtra("eventBtConnected")
        val hasBtIdentityEvent = intent.hasExtra("eventBtName") || intent.hasExtra("eventBtAddr")
        val hasBtEvent = hasBtStateEvent || hasBtIdentityEvent
        val eventBtConnected = intent.getBooleanExtra("eventBtConnected", false)

        if (hasBtStateEvent) {
            // A real connect/disconnect event owns the connected state.
            cacheBtEvent(ctx, eventBtName, eventBtAddr, eventBtConnected)
            setBtTsNow(ctx)
        } else if (hasBtIdentityEvent) {
            // Service start/retry ticks may only carry the last known device identity.
            // Do not treat those as "disconnected" just because eventBtConnected is absent.
            cacheBtIdentity(ctx, eventBtName, eventBtAddr)
        }

        val schedules = ScheduleStore.getAll(ctx).filter { it.enabled }
        if (schedules.isNotEmpty()) {
            logScheduleEvaluationIfNeeded(
                ctx = ctx,
                scheduleCount = schedules.size,
                profile = ProfileStore.getCurrent(ctx),
                reason = alarmReason ?: wifiReason ?: if (hasBtEvent) "bluetooth_event" else "tick"
            )
        }

        // "Trigger" schedules (Wi-Fi/BT based) use wifiSsid/btDeviceName/btDeviceAddress.
        val needsWifiSsid = schedules.any { !it.wifiSsid.isNullOrBlank() }
        val needsBtInfo = schedules.any { !it.btDeviceName.isNullOrBlank() || !it.btDeviceAddress.isNullOrBlank() }

        var ssid = cachedSsid(ctx)

        val btName = cachedBtName(ctx)
        val btAddr = cachedBtAddr(ctx)
        var btConnected = cachedBtConnected(ctx)
        val btAgeMs = btAgeMs(ctx)

        if (needsBtInfo && btConnected && btAgeMs > BT_FRESHNESS_MS && !hasBtStateEvent) {
            // Do not drop an active Bluetooth schedule only because the cache is old.
            // Some cars/Android builds do not emit frequent profile updates while the device remains connected.
            // Explicit disconnect events are still handled immediately below through eventBtConnected=false.
            val actuallyConnected = isAnyBtProfileConnected(ctx)
            if (actuallyConnected == true) {
                setBtTsNow(ctx)
            } else {
                Log.w(TAG, "BT cache stale (${btAgeMs}ms), but no explicit disconnect event; keeping cached connection")
            }
        }

        // On some devices ("use mobile data"/VPN/poor Wi-Fi), the active/default network can be CELLULAR even while Wi-Fi is still connected.
        // We therefore treat Wi-Fi as connected when *any* network has TRANSPORT_WIFI.
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiCaps = currentWifiCaps(cm)
        val wifiConnected = wifiCaps != null

        // If Wi-Fi is connected but SSID hasn't been cached yet (or was unavailable during service start), attempt to read it directly here.
        // This prevents "Wi-Fi schedule does nothing" when enabling a schedule while already connected, and also makes time-boundary ticks independent from the Wi-Fi service timing.
        if (needsWifiSsid && wifiConnected && ssid.isNullOrBlank()) {
            // Try to read SSID directly from the current Wi-Fi network capabilities (even if not default).
            ssid = tryReadCurrentSsid(ctx, wifiCaps)
            if (!ssid.isNullOrBlank()) {
                // Persist into the same cache that WifiTriggerService uses so future ticks can reuse it.
                ctx.getSharedPreferences(PREFS_WIFI, Context.MODE_PRIVATE).edit {
                    putString(KEY_LAST_SSID, ssid)
                }
            }
        }

        // IMPORTANT:
        // SchedulePlanner fires alarms slightly early (EARLY_WINDOW_MS) for reliability.
        // If we evaluated using the receiver's wall-clock time, we could be one minute early (e.g., tick at 19:09:50 for a 19:10 boundary) which would delay the schedule.
        // We therefore use the real boundary timestamp when available.
        val boundaryMs = intent.getLongExtra("boundary_ms", -1L)
        val now = if (boundaryMs > 0 && alarmReason == "time_boundary") {
            Calendar.getInstance().apply { timeInMillis = boundaryMs }
        } else {
            Calendar.getInstance()
        }
        val nowMs = now.timeInMillis
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val todayYmd = run {
            val y = now.get(Calendar.YEAR)
            val m = now.get(Calendar.MONTH) + 1
            val d = now.get(Calendar.DAY_OF_MONTH)
            y * 10000 + m * 100 + d
        }
        val todayBit = ScheduleStore.Days.fromCalendarDay(now.get(Calendar.DAY_OF_WEEK))

        dbg("tick: ssid=$ssid btName=$btName btAddr=$btAddr btConnected=$btConnected btAgeMs=$btAgeMs now=$nowMinutes today=$todayYmd wifiReason=$wifiReason hasBtEvent=$hasBtEvent stateEvent=$hasBtStateEvent/$eventBtConnected")

        val lastSource = getLastActivationSource(ctx)
        val hardWifiDisconnect = (wifiReason == "lost") || !wifiConnected

        // Wi-Fi SSID guard
        if (needsWifiSsid && ssid.isNullOrBlank()) {
            if (hardWifiDisconnect) {
                dbg("SSID null and Wi-Fi hard-disconnected -> allow disconnect evaluation")
                logWifiScheduleStateIfNeeded(ctx, "disconnected", "Wi-Fi schedule waiting for disconnect evaluation")
            } else {
                dbg("Wi-Fi schedules exist but ssid is null while connected -> wait for SSID (reason=$wifiReason)")
                logWifiScheduleStateIfNeeded(
                    ctx,
                    "waiting_for_ssid:${wifiReason ?: alarmReason ?: "tick"}",
                    "Wi-Fi schedule waiting for SSID. Check Location permission/services if this repeats."
                )
                updateNextAlarmAndNotifyIfChanged(ctx)
                return
            }
        }

        // BT name guard
        if (needsBtInfo && btName.isNullOrBlank() && btAddr.isNullOrBlank() && !hasBtEvent) {
            AppLogStore.append(ctx, "Schedule", "Match failed reason=no_active_schedule")
            updateNextAlarmAndNotifyIfChanged(ctx)
            return
        }

        fun isRangeAction(action: ScheduleStore.Action) =
            action in setOf(
                ScheduleStore.Action.ENABLE_AND_DISABLE,
                ScheduleStore.Action.DISABLE_AND_ENABLE
            )

        val matches = mutableListOf<ScheduleStore.Schedule>()

        for (s in schedules) {
            val ok = when {
                s.isLocationSchedule() -> false

                !s.wifiSsid.isNullOrBlank() -> {
                    val appliesToday = when (s.type) {
                        ScheduleStore.Type.WEEKLY -> (s.daysMask and todayBit) != 0
                        ScheduleStore.Type.ONE_TIME ->
                            (s.startDate > 0 && s.endDate > 0 && todayYmd in s.startDate..s.endDate)
                    }

                    val timeOk = (s.startMinutes == 0 && s.endMinutes >= 1439) ||
                        inTimeRange(nowMinutes, s.startMinutes, s.endMinutes)

                    val active = appliesToday && timeOk && wifiConnected && ssidMatches(ssid, s.wifiSsid)
                    if (active) {
                        if (isRangeAction(s.action)) true
                        else shouldFireOneShotConn(ctx, s, token = "WIFI:${s.wifiSsid}:${s.action.name}")
                    } else {
                        ScheduleRuntimeStore.clearLastFiredToken(ctx, s.id)
                        false
                    }
                }

                !s.btDeviceName.isNullOrBlank() || !s.btDeviceAddress.isNullOrBlank() -> {
                    val appliesToday = when (s.type) {
                        ScheduleStore.Type.WEEKLY -> (s.daysMask and todayBit) != 0
                        ScheduleStore.Type.ONE_TIME ->
                            (s.startDate > 0 && s.endDate > 0 && todayYmd in s.startDate..s.endDate)
                    }

                    val timeOk = (s.startMinutes == 0 && s.endMinutes >= 1439) ||
                        inTimeRange(nowMinutes, s.startMinutes, s.endMinutes)

                    val active = appliesToday && timeOk && btConnected && bluetoothScheduleMatches(
                        scheduleName = s.btDeviceName,
                        scheduleAddress = s.btDeviceAddress,
                        currentName = btName,
                        currentAddress = btAddr
                    )
                    if (active) {
                        if (isRangeAction(s.action)) true
                        else shouldFireOneShotConn(ctx, s, token = "BT:${s.btDeviceAddress ?: s.btDeviceName}:${s.action.name}")
                    } else {
                        ScheduleRuntimeStore.clearLastFiredToken(ctx, s.id)
                        false
                    }
                }

                else -> {
                    val appliesToday = when (s.type) {
                        ScheduleStore.Type.WEEKLY -> (s.daysMask and todayBit) != 0
                        ScheduleStore.Type.ONE_TIME ->
                            (s.startDate > 0 && s.endDate > 0 && todayYmd in s.startDate..s.endDate)
                    }

                    if (!appliesToday) {
                        ScheduleRuntimeStore.clearLastFiredToken(ctx, s.id)
                        false
                    } else {
                        if (isRangeAction(s.action)) {
                            inTimeRange(nowMinutes, s.startMinutes, s.endMinutes)
                        } else {
                            val targetMs = atMinutesToday(now, s.startMinutes)
                            val due = abs(nowMs - targetMs) <= SINGLE_FIRE_WINDOW_MS
                            if (!due) {
                                false
                            } else {
                                val token = "TIME:${todayYmd}:${s.startMinutes}:${s.action.name}"
                                shouldFireOneShotTime(ctx, s, token)
                            }
                        }
                    }
                }
            }

            if (ok) matches += s
        }

        val hadEnableAndDisable = ScheduleRuntimeStore.hadEnableAndDisable(ctx)
        val hadDisableAndEnable = ScheduleRuntimeStore.hadDisableAndEnable(ctx)

        val hasEnableAndDisableNow = matches.any { it.action == ScheduleStore.Action.ENABLE_AND_DISABLE }
        val hasDisableAndEnableNow = matches.any { it.action == ScheduleStore.Action.DISABLE_AND_ENABLE }

        // No matches: handle exit actions for range types
        if (matches.isEmpty()) {
            // Leaving any active schedule zone -> manual override no longer applies.
            // Keep the previous value for exit handling below.
            val manualOverrideWasActive = ScheduleRuntimeStore.isManualOverrideActive(ctx)
            val previousActiveRangeId = ScheduleRuntimeStore.getActiveRangeScheduleId(ctx)
            val previousActiveRange = schedules.firstOrNull { it.id == previousActiveRangeId }
            if (manualOverrideWasActive) {
                ScheduleRuntimeStore.setManualOverrideActive(ctx, false)
                ScheduleRuntimeStore.clearManualOverrideScheduleId(ctx)
            }
            val manualSchedulePauseWasActive = ScheduleRuntimeStore.isManualSchedulePauseActive(ctx)
            val manualSchedulePauseScheduleId = ScheduleRuntimeStore.getManualSchedulePauseScheduleId(ctx)
            val manualSchedulePauseAppliesToPrevious = manualSchedulePauseWasActive && manualSchedulePauseScheduleId == previousActiveRangeId
            ScheduleRuntimeStore.clearActiveRangeScheduleId(ctx)

            val effectiveLastSource = when {
                previousActiveRange?.wifiSsid?.isNotBlank() == true -> SOURCE_WIFI
                (previousActiveRange?.btDeviceName?.isNotBlank() == true || previousActiveRange?.btDeviceAddress?.isNotBlank() == true) -> SOURCE_BT
                previousActiveRange != null -> SOURCE_TIME
                else -> lastSource
            }

            val shouldExitOnce =
                when (effectiveLastSource) {
                    // Wi-Fi/BT schedules can also have time windows now.
                    // If we leave the active range while still connected, we still need to revert.
                    SOURCE_WIFI -> hardWifiDisconnect || isTimeBoundaryTick || alarmReason == "boot_watchdog" || alarmReason == "unlock_watchdog"
                    SOURCE_BT -> (hasBtStateEvent && !eventBtConnected) || isTimeBoundaryTick || alarmReason == "boot_watchdog" || alarmReason == "unlock_watchdog"
                    SOURCE_TIME -> true
                    else -> false
                }

            if (shouldExitOnce) {
                // ENABLE_AND_DISABLE => disable on exit if we owned enable
                // If a manual override happened during an active range and ownership got cleared by older builds, still revert once on exit.
                if (
                    hadEnableAndDisable &&
                        (ScheduleRuntimeStore.wasEnabledBySchedule(ctx) || manualOverrideWasActive)
                ) {
                    if (SwitchModeStore.isBaseEnabled(ctx)) {
                        SwitchModeStore.setEnabledBySchedule(ctx, false)
                        ScheduleRuntimeStore.setEnabledBySchedule(ctx, false)
                    }
                }

                // DISABLE_AND_ENABLE => enable on exit if we owned disable
                if (
                    hadDisableAndEnable &&
                        !manualSchedulePauseAppliesToPrevious &&
                        (ScheduleRuntimeStore.wasDisabledBySchedule(ctx) || manualOverrideWasActive)
                ) {
                    if (!SwitchModeStore.isBaseEnabled(ctx)) {
                        SwitchModeStore.setEnabledBySchedule(ctx, true)
                        ScheduleRuntimeStore.setDisabledBySchedule(ctx, false)
                    }
                } else if (hadDisableAndEnable && manualSchedulePauseAppliesToPrevious) {
                    AppLogStore.append(
                        ctx,
                        "Schedule",
                        "schedule_skipped id=$manualSchedulePauseScheduleId action=exit_enable source=$effectiveLastSource reason=manual_disabled"
                    )
                }
            }

            if (manualSchedulePauseWasActive) {
                ScheduleRuntimeStore.setManualSchedulePauseActive(ctx, false)
                AppLogStore.append(
                    ctx,
                    "Schedule",
                    "schedule_manual_pause_cleared id=$manualSchedulePauseScheduleId reason=no_active_match"
                )
            }

            if (hadEnableAndDisable) ScheduleRuntimeStore.setHadEnableAndDisable(ctx, false)
            if (hadDisableAndEnable) ScheduleRuntimeStore.setHadDisableAndEnable(ctx, false)

            updateNextAlarmAndNotifyIfChanged(ctx)
            return
        }

        // Keep runtime flags updated (range schedules)
        if (hadEnableAndDisable != hasEnableAndDisableNow) {
            ScheduleRuntimeStore.setHadEnableAndDisable(ctx, hasEnableAndDisableNow)
        }
        if (hadDisableAndEnable != hasDisableAndEnableNow) {
            ScheduleRuntimeStore.setHadDisableAndEnable(ctx, hasDisableAndEnableNow)
        }

        val target = pickWinningMatch(matches, nowMinutes)
        if (matches.size > 1) {
            val overwritten = matches
                .filter { it.id != target.id }
                .joinToString(separator = ",") { "#${it.id}:${ScheduleInsights.scheduleDisplayName(it)}" }
            AppLogStore.append(
                ctx,
                "Schedule",
                "schedule_conflict active=#${target.id}:${ScheduleInsights.scheduleDisplayName(target)} overwritten=$overwritten"
            )
        }
        AppLogStore.append(
            ctx,
            "Schedule",
            "schedule_match id=${target.id} name=${ScheduleInsights.scheduleDisplayName(target)} action=${target.action.name} profile=${target.profile.ifBlank { "-" }} day=$todayBit time=$nowMinutes"
        )
        dbg("matches=${matches.size} -> winner id=${target.id} profile=${target.profile} start=${target.startMinutes} end=${target.endMinutes} action=${target.action}")
        val source = when {
            !target.wifiSsid.isNullOrBlank() -> SOURCE_WIFI
            !target.btDeviceName.isNullOrBlank() || !target.btDeviceAddress.isNullOrBlank() -> SOURCE_BT
            else -> SOURCE_TIME
        }

        // If the user manually toggled Switchly while a RANGE schedule is active, don't let the schedule instantly override the user's choice.
        // The override clears automatically once the schedule condition is no longer active.
        val manualOverride = ScheduleRuntimeStore.isManualOverrideActive(ctx)
        val isRangeTarget = target.action == ScheduleStore.Action.ENABLE_AND_DISABLE ||
            target.action == ScheduleStore.Action.DISABLE_AND_ENABLE
        if (manualOverride && isRangeTarget) {
            val manualOverrideScheduleId = ScheduleRuntimeStore.getManualOverrideScheduleId(ctx)
            if (manualOverrideScheduleId == target.id) {
                dbg("Manual override active for current range -> skip schedule apply (id=${target.id}, source=$source)")
                updateNextAlarmAndNotifyIfChanged(ctx)
                return
            }

            dbg("Manual override owner changed ($manualOverrideScheduleId -> ${target.id}) -> clear override and apply winner")
            ScheduleRuntimeStore.setManualOverrideActive(ctx, false)
            ScheduleRuntimeStore.clearManualOverrideScheduleId(ctx)
            if (ScheduleRuntimeStore.getManualSchedulePauseScheduleId(ctx) == manualOverrideScheduleId) {
                ScheduleRuntimeStore.setManualSchedulePauseActive(ctx, false)
            }
        }

        // Stats: count schedule executions (not every tick)
        // For one-shot schedules: every successful fire is an execution.
        // For range schedules: only count when we ENTER the active range.
        val isRange = target.action == ScheduleStore.Action.ENABLE_AND_DISABLE ||
            target.action == ScheduleStore.Action.DISABLE_AND_ENABLE

        val executedNow = if (!isRange) {
            true
        } else {
            // Use the existing per-schedule "last fired token" to avoid double counting.
            // It's cleared when schedule becomes inactive.
            val ymd = Calendar.getInstance().let { cal ->
                val y = cal.get(Calendar.YEAR)
                val m = cal.get(Calendar.MONTH) + 1
                val d = cal.get(Calendar.DAY_OF_MONTH)
                (y * 10000) + (m * 100) + d
            }
            // Build a stable token so we count the range schedule only once per active period.
            // (Token is cleared when schedule becomes inactive.)
            val wifi = target.wifiSsid ?: ""
            val bt = target.btDeviceAddress ?: target.btDeviceName ?: ""
            val token = "RANGE_${ymd}_${target.startMinutes}_${target.endMinutes}_${target.action}" + "_${target.profile}_${wifi}_${bt}"
            // Choose the appropriate token gate (time/conn doesn't really matter here)
            shouldFireOneShotTime(ctx, target, token)
        }

        val applied = applySchedule(ctx, target, source)
        if (applied) {
            if (executedNow) {
                ScheduleExecutionCountStore.incrementToday(ctx)
            }
            ScheduleRuntimeStore.markExecutedNow(ctx)
        }
    }

    /**
     * Apply schedule action + profile.
     * For range schedules we must set "ownership" even if the baseEnabled state did not change.
     * Otherwise exit-revert can't happen reliably.
     */
    private fun handleLocationTransition(ctx: Context, scheduleId: Int, transition: Int) {

        val schedule = ScheduleStore.getAll(ctx).firstOrNull {
            it.id == scheduleId && it.enabled && it.isLocationSchedule()
        } ?: return

        val transitionKey = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
            else -> return
        }

        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val todayBit = ScheduleStore.Days.fromCalendarDay(now.get(Calendar.DAY_OF_WEEK))

        val wantsEnter = schedule.locationTrigger == ScheduleStore.LocationTrigger.ENTER ||
            schedule.locationTrigger == ScheduleStore.LocationTrigger.ENTER_EXIT
        val wantsExit = schedule.locationTrigger == ScheduleStore.LocationTrigger.EXIT ||
            schedule.locationTrigger == ScheduleStore.LocationTrigger.ENTER_EXIT

        val isEnter = transition == Geofence.GEOFENCE_TRANSITION_ENTER
        val isExit = transition == Geofence.GEOFENCE_TRANSITION_EXIT
        if ((isEnter && !wantsEnter) || (isExit && !wantsExit)) return

        val pairedMode = schedule.locationTrigger == ScheduleStore.LocationTrigger.ENTER_EXIT && (
            schedule.action == ScheduleStore.Action.ENABLE_AND_DISABLE ||
                schedule.action == ScheduleStore.Action.DISABLE_AND_ENABLE
            )

        if (isExit && pairedMode && !ScheduleRuntimeStore.isLocationArmed(ctx, schedule.id)) {
            return
        }

        val withinDayAndTime = (schedule.daysMask and todayBit) != 0 && (
            (schedule.startMinutes == 0 && schedule.endMinutes >= 1439) ||
                inTimeRange(nowMinutes, schedule.startMinutes, schedule.endMinutes)
            )

        if (isEnter && !withinDayAndTime) return
        if (isExit && !withinDayAndTime && !pairedMode) return

        val effectiveAction = when (schedule.locationTrigger) {
            ScheduleStore.LocationTrigger.ENTER -> if (isEnter) schedule.action else null
            ScheduleStore.LocationTrigger.EXIT -> if (isExit) schedule.action else null
            ScheduleStore.LocationTrigger.ENTER_EXIT -> when (schedule.action) {
                ScheduleStore.Action.ENABLE_AND_DISABLE ->
                    if (isEnter) ScheduleStore.Action.ENABLE else if (isExit) ScheduleStore.Action.DISABLE else null
                ScheduleStore.Action.DISABLE_AND_ENABLE ->
                    if (isEnter) ScheduleStore.Action.DISABLE else if (isExit) ScheduleStore.Action.ENABLE else null
                else -> if (isEnter || isExit) schedule.action else null
            }
            null -> null
        } ?: return

        val lastMs = ScheduleRuntimeStore.getLastLocationTransitionMs(ctx, schedule.id, transitionKey)
        val cooldownMs = schedule.locationCooldownMinutes.coerceAtLeast(0) * 60_000L
        val repeatGuardMs = maxOf(cooldownMs, LOCATION_DUPLICATE_TRANSITION_WINDOW_MS)
        val nowMs = System.currentTimeMillis()
        if (lastMs > 0L && nowMs - lastMs < repeatGuardMs) {
            return
        }
        ScheduleRuntimeStore.setLastLocationTransitionMs(ctx, schedule.id, transitionKey, nowMs)

        val applied = applySchedule(ctx, schedule.copy(action = effectiveAction), SOURCE_LOCATION)
        if (!applied) {
            if (isExit && ScheduleRuntimeStore.isManualSchedulePausedFor(ctx, schedule.id)) {
                ScheduleRuntimeStore.setManualSchedulePauseActive(ctx, false)
                AppLogStore.append(
                    ctx,
                    "Schedule",
                    "schedule_manual_pause_cleared reason=location_exit id=${schedule.id}"
                )
            }
            if (isExit && PauseUntilStore.shouldEndOnLocationExit(ctx, schedule.id)) {
                SwitchModeStore.cancelTemporaryDisable(ctx)
                PauseUntilStore.clearLocationPause(ctx)
                AppLogStore.append(ctx, "Schedule", "pause_until_location_exit_completed id=${schedule.id}")
            }
            return
        }
        ScheduleRuntimeStore.markExecutedNow(ctx)

        if (isExit && PauseUntilStore.shouldEndOnLocationExit(ctx, schedule.id)) {
            SwitchModeStore.cancelTemporaryDisable(ctx)
            PauseUntilStore.clearLocationPause(ctx)
            AppLogStore.append(ctx, "Schedule", "pause_until_location_exit_completed id=${schedule.id}")
        }

        if (pairedMode) {
            if (isEnter) {
                ScheduleRuntimeStore.setLocationArmed(ctx, schedule.id, true)
            } else if (isExit) {
                ScheduleRuntimeStore.setLocationArmed(ctx, schedule.id, false)
            }
        }

        ScheduleExecutionCountStore.incrementToday(ctx)
        AppLogStore.append(
            ctx,
            "Schedule",
            "Applied location schedule id=${schedule.id} transition=$transitionKey action=${effectiveAction.name}"
        )
    }

    private fun dbg(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    private fun logScheduleEvaluationIfNeeded(
        ctx: Context,
        scheduleCount: Int,
        profile: String?,
        reason: String
    ) {
        val nowMs = System.currentTimeMillis()
        val cleanProfile = profile ?: "-"
        val cleanReason = reason.ifBlank { "tick" }
        val signature = "$cleanProfile|$scheduleCount|$cleanReason"
        val prefs = ctx.getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(KEY_LAST_EVAL_LOG_TS, 0L)
        val lastSignature = prefs.getString(KEY_LAST_EVAL_LOG_SIGNATURE, null)

        if (lastSignature == signature && nowMs - lastTs < SCHEDULE_EVAL_LOG_THROTTLE_MS) {
            return
        }

        prefs.edit {
            putLong(KEY_LAST_EVAL_LOG_TS, nowMs)
            putString(KEY_LAST_EVAL_LOG_SIGNATURE, signature)
        }
        AppLogStore.append(
            ctx,
            "Schedule",
            "Evaluating schedules profile=$cleanProfile count=$scheduleCount reason=$cleanReason"
        )
    }

    private fun applySchedule(ctx: Context, s: ScheduleStore.Schedule, source: String): Boolean {
        val currentProfile = ProfileStore.getCurrent(ctx)
        val tempOverrideActive = SwitchModeStore.hasActiveTemporaryOverride(ctx)
        val profileChanged = !tempOverrideActive && currentProfile != s.profile

        val baseEnabledBefore = SwitchModeStore.isBaseEnabled(ctx)

        val isRangeEnableDisable = s.action == ScheduleStore.Action.ENABLE_AND_DISABLE
        val isRangeDisableEnable = s.action == ScheduleStore.Action.DISABLE_AND_ENABLE

        val (shouldWriteEnabled, newEnabled) = when (s.action) {
            ScheduleStore.Action.ENABLE_AND_DISABLE -> {
                if (!baseEnabledBefore) true to true else false to baseEnabledBefore
            }

            ScheduleStore.Action.DISABLE_AND_ENABLE -> {
                if (baseEnabledBefore) true to false else false to baseEnabledBefore
            }

            ScheduleStore.Action.ENABLE -> {
                if (!baseEnabledBefore) true to true else false to baseEnabledBefore
            }

            ScheduleStore.Action.DISABLE -> {
                if (baseEnabledBefore) true to false else false to baseEnabledBefore
            }

            ScheduleStore.Action.TOGGLE -> {
                true to !baseEnabledBefore
            }
        }

        if (shouldWriteEnabled) {
            if (newEnabled && ScheduleRuntimeStore.isManualSchedulePausedFor(ctx, s.id)) {
                AppLogStore.append(
                    ctx,
                    "Schedule",
                    "schedule_skipped id=${s.id} name=${ScheduleInsights.scheduleDisplayName(s)} action=${s.action.name} source=$source reason=manual_disabled"
                )
                dbg("Schedule enable skipped by manual disable pause (id=${s.id}, source=$source)")
                updateNextAlarmAndNotifyIfChanged(ctx)
                return false
            }
            SwitchModeStore.setEnabledBySchedule(ctx, newEnabled)
        }

        val baseEnabledAfter = SwitchModeStore.isBaseEnabled(ctx)
        val stateActuallyChanged = baseEnabledAfter != baseEnabledBefore
        val stateWriteBlocked = shouldWriteEnabled && baseEnabledAfter != newEnabled

        // Ownership flags
        // Range schedules: claim ownership while active only if the target state is currently true.
        // This prevents false ownership when a DISABLE write is blocked by NFC lock.
        if (isRangeEnableDisable) {
            val ownsEnable = baseEnabledAfter
            ScheduleRuntimeStore.setEnabledBySchedule(ctx, ownsEnable)
            if (ownsEnable) {
                ScheduleRuntimeStore.setDisabledBySchedule(ctx, false)
            }
        } else if (isRangeDisableEnable) {
            val ownsDisable = !baseEnabledAfter
            ScheduleRuntimeStore.setDisabledBySchedule(ctx, ownsDisable)
            if (ownsDisable) {
                ScheduleRuntimeStore.setEnabledBySchedule(ctx, false)
            }
        } else {
            // One-shot schedules: only set ownership when we actually changed state.
            if (stateActuallyChanged) {
                when (s.action) {
                    ScheduleStore.Action.ENABLE -> {
                        if (baseEnabledAfter) {
                            ScheduleRuntimeStore.setEnabledBySchedule(ctx, true)
                            ScheduleRuntimeStore.setDisabledBySchedule(ctx, false)
                        }
                    }

                    ScheduleStore.Action.DISABLE -> {
                        if (!baseEnabledAfter) {
                            ScheduleRuntimeStore.setDisabledBySchedule(ctx, true)
                            ScheduleRuntimeStore.setEnabledBySchedule(ctx, false)
                        }
                    }

                    ScheduleStore.Action.TOGGLE -> {
                        if (baseEnabledAfter) {
                            ScheduleRuntimeStore.setEnabledBySchedule(ctx, true)
                            ScheduleRuntimeStore.setDisabledBySchedule(ctx, false)
                        } else {
                            ScheduleRuntimeStore.setDisabledBySchedule(ctx, true)
                            ScheduleRuntimeStore.setEnabledBySchedule(ctx, false)
                        }
                    }

                    else -> Unit
                }
            }
        }

        if (stateWriteBlocked && !newEnabled && SwitchModeStore.isNfcRequiredForDisable(ctx)) {
            // Visible in schedules screen banner so users understand why end-times may not disable.
            ScheduleRuntimeStore.markDisableBlockedByNfc(ctx)
            AppLogStore.append(ctx, "Schedule", "Match failed reason=disable_blocked_by_nfc")
            dbg("Schedule disable blocked by NFC lock (id=${s.id}, source=$source)")
        }

        if (profileChanged) {
            ProfileStore.setCurrent(ctx, s.profile)
        } else if (tempOverrideActive && currentProfile != s.profile) {
            if (SwitchModeStore.hasActiveTemporaryEnable(ctx)) {
                SwitchModeStore.setTemporaryEnableRestoreProfileFromSchedule(ctx, s.profile)
                dbg("Temporary enable active -> queue schedule profile restore (id=${s.id}, source=$source, target=${s.profile})")
            } else {
                dbg("Temporary override active -> skip schedule profile switch (id=${s.id}, source=$source, target=${s.profile})")
            }
        }

        if (profileChanged || (stateActuallyChanged && baseEnabledAfter)) {
            BlockingRuntime.ensureRunning(ctx)
        }

        AppLogStore.append(
            ctx,
            "Schedule",
            "schedule_apply id=${s.id} name=${ScheduleInsights.scheduleDisplayName(s)} action=${s.action.name} profile=${s.profile.ifBlank { "-" }} source=$source enabledBefore=$baseEnabledBefore enabledAfter=$baseEnabledAfter profileChanged=$profileChanged"
        )

        if (isRangeEnableDisable || isRangeDisableEnable) {
            ScheduleRuntimeStore.setActiveRangeScheduleId(ctx, s.id)
        } else {
            ScheduleRuntimeStore.clearActiveRangeScheduleId(ctx)
        }

        val prevSource = getLastActivationSource(ctx)
        if (prevSource != source) {
            setLastActivationSource(ctx, source)
        }

        // range flags: set based on current applied schedule
        if (ScheduleRuntimeStore.hadEnableAndDisable(ctx) != isRangeEnableDisable) {
            ScheduleRuntimeStore.setHadEnableAndDisable(ctx, isRangeEnableDisable)
        }
        if (ScheduleRuntimeStore.hadDisableAndEnable(ctx) != isRangeDisableEnable) {
            ScheduleRuntimeStore.setHadDisableAndEnable(ctx, isRangeDisableEnable)
        }

        updateNextAlarmAndNotifyIfChanged(ctx)
        return true
    }

    private fun shouldFireOneShotTime(ctx: Context, s: ScheduleStore.Schedule, token: String): Boolean {
        val last = ScheduleRuntimeStore.getLastFiredToken(ctx, s.id)
        if (last == token) return false
        ScheduleRuntimeStore.setLastFiredToken(ctx, s.id, token)
        return true
    }

    private fun shouldFireOneShotConn(ctx: Context, s: ScheduleStore.Schedule, token: String): Boolean {
        val last = ScheduleRuntimeStore.getLastFiredToken(ctx, s.id)
        if (last == token) return false
        ScheduleRuntimeStore.setLastFiredToken(ctx, s.id, token)
        return true
    }

    private fun pickWinningMatch(
        matches: List<ScheduleStore.Schedule>,
        nowMin: Int
    ): ScheduleStore.Schedule {
        return matches.maxWithOrNull(
            compareBy<ScheduleStore.Schedule> { scheduleSourcePriority(it) }
                .thenBy { activeStartSortKey(it, nowMin) }
                .thenBy { actionPriority(it.action) }
        ) ?: matches.first()
    }

    private fun scheduleSourcePriority(s: ScheduleStore.Schedule): Int = when {
        s.isLocationSchedule() -> 3
        !s.wifiSsid.isNullOrBlank() || !s.btDeviceName.isNullOrBlank() || !s.btDeviceAddress.isNullOrBlank() -> 2
        else -> 1
    }

    private fun activeStartSortKey(s: ScheduleStore.Schedule, nowMin: Int): Int {
        if (!inTimeRange(nowMin, s.startMinutes, s.endMinutes)) return Int.MIN_VALUE

        return if (s.endMinutes > s.startMinutes || nowMin >= s.startMinutes) {
            s.startMinutes
        } else {
            // Overnight range that started yesterday (e.g. 22:00-06:00 while now is 01:00).
            s.startMinutes - (24 * 60)
        }
    }

    private fun actionPriority(action: ScheduleStore.Action): Int = when (action) {
        ScheduleStore.Action.ENABLE,
        ScheduleStore.Action.DISABLE,
        ScheduleStore.Action.TOGGLE -> 1
        ScheduleStore.Action.ENABLE_AND_DISABLE,
        ScheduleStore.Action.DISABLE_AND_ENABLE -> 0
    }

    private fun bluetoothScheduleMatches(
        scheduleName: String?,
        scheduleAddress: String?,
        currentName: String?,
        currentAddress: String?
    ): Boolean {
        val configuredAddress = normalizeBtAddress(scheduleAddress)
        val configuredName = scheduleName?.trim().orEmpty()
        val configuredNameAsAddress = normalizeBtAddress(configuredName)
        val activeAddress = normalizeBtAddress(currentAddress)
        val activeName = currentName?.trim().orEmpty()

        return when {
            configuredAddress.isNotEmpty() && activeAddress.isNotEmpty() -> configuredAddress == activeAddress
            configuredNameAsAddress.isNotEmpty() && activeAddress.isNotEmpty() -> configuredNameAsAddress == activeAddress
            configuredName.isNotEmpty() && activeName.isNotEmpty() -> configuredName.equals(activeName, ignoreCase = true)
            else -> false
        }
    }

    private fun normalizeBtAddress(value: String?): String =
        value
            ?.trim()
            ?.takeIf { it.matches(Regex("""(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}""")) }
            ?.uppercase()
            .orEmpty()

    private fun inTimeRange(nowMin: Int, startMin: Int, endMin: Int): Boolean {
        if (endMin == startMin) return false

        if (endMin > startMin) {
            return nowMin in startMin until endMin
        }

        // Overnight (e.g., 22:00 - 06:00)
        return nowMin >= startMin || nowMin < endMin
    }

    private fun atMinutesToday(base: Calendar, minutes: Int): Long {
        return (base.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minutes/60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun updateNextAlarmAndNotifyIfChanged(ctx: Context) {
        val before = SchedulePlanner.getNextBoundaryMillis(ctx)
        SchedulePlanner.updateNextAlarm(ctx)
        val after = SchedulePlanner.getNextBoundaryMillis(ctx)
        if (before != after) {
            SchedulePlanner.notifyNextChanged(ctx)
        }
    }

    private fun isAnyBtProfileConnected(ctx: Context): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!granted) return null
        }

        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val adapter = bm.adapter ?: return null

        return try {
            val a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED
            val hsp = adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED
            a2dp || hsp
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun cachedSsid(ctx: Context) =
        ctx.getSharedPreferences(PREFS_WIFI, Context.MODE_PRIVATE).getString(KEY_LAST_SSID, null)

    private fun allNetworksCompat(cm: ConnectivityManager): Array<Network> {
        val value = runCatching {
            cm.javaClass.getMethod("getAllNetworks").invoke(cm)
        }.getOrNull()
        return (value as? Array<*>)?.filterIsInstance<Network>()?.toTypedArray() ?: emptyArray()
    }

    private fun wifiConnectionInfoCompat(wifiManager: WifiManager): WifiInfo? {
        return runCatching {
            wifiManager.javaClass.getMethod("getConnectionInfo").invoke(wifiManager) as? WifiInfo
        }.getOrNull()
    }
    /**
     * Returns the capabilities for any currently connected Wi‑Fi network.
     * We intentionally do NOT use [ConnectivityManager.activeNetwork] because the "default" network can be CELLULAR (or a VPN) while Wi‑Fi is still connected.
     */
    private fun currentWifiCaps(cm: ConnectivityManager): NetworkCapabilities? {
        return try {
            for (n in allNetworksCompat(cm)) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return caps
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Best-effort SSID read for devices where the Wi-Fi trigger service hasn't cached it yet.
     * Returns null if unavailable (e.g., Location permission missing/Location OFF).
     */
    private fun tryReadCurrentSsid(ctx: Context, wifiCaps: NetworkCapabilities?): String? {
        return try {
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Prefer the provided Wi‑Fi caps (may not be the active/default network)
                val capSsid = (wifiCaps?.transportInfo as? WifiInfo)?.ssid
                if (!capSsid.isNullOrBlank()) {
                    capSsid
                } else {
                    // Some OEMs return null transportInfo even while connected.
                    // Fallback to WifiManager.connectionInfo (still requires Location permission).
                    val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifiConnectionInfoCompat(wifiManager)?.ssid
                }
            } else {
                val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiConnectionInfoCompat(wifiManager)?.ssid
            } ?: return null

            normalizeSsid(raw)
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun ssidMatches(current: String?, expected: String?): Boolean {
        val cleanCurrent = normalizeSsid(current) ?: return false
        val cleanExpected = normalizeSsid(expected) ?: return false
        return cleanCurrent.equals(cleanExpected, ignoreCase = true)
    }

    private fun normalizeSsid(raw: String?): String? {
        val value = raw
            ?.trim()
            ?.removePrefix(""")
            ?.removeSuffix(""")
            ?.trim()
            ?: return null

        return value.takeIf {
            it.isNotBlank() &&
                !it.equals("<unknown ssid>", ignoreCase = true) &&
                !it.equals("unknown ssid", ignoreCase = true)
        }
    }

    private fun logWifiScheduleStateIfNeeded(ctx: Context, key: String, message: String) {
        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences(PREFS_WIFI, Context.MODE_PRIVATE)
        val lastKey = prefs.getString(KEY_WIFI_LOG_STATE, null)
        val lastAt = prefs.getLong(KEY_WIFI_LOG_AT, 0L)
        if (lastKey == key && now - lastAt < WIFI_LOG_THROTTLE_MS) return

        prefs.edit {
            putString(KEY_WIFI_LOG_STATE, key)
            putLong(KEY_WIFI_LOG_AT, now)
        }
        AppLogStore.append(ctx, "Schedule", message)
    }

    private fun cacheBtEvent(ctx: Context, name: String?, addr: String?, connected: Boolean) {
        ctx.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE).edit {
            putString(KEY_BT_NAME, name?.trim()?.takeIf { it.isNotBlank() })
            putString(KEY_BT_ADDR, addr?.trim()?.takeIf { it.isNotBlank() })
            putBoolean(KEY_BT_CONNECTED, connected)
        }
    }

    private fun cacheBtIdentity(ctx: Context, name: String?, addr: String?) {
        val cleanName = name?.trim()?.takeIf { it.isNotBlank() }
        val cleanAddr = addr?.trim()?.takeIf { it.isNotBlank() }
        if (cleanName == null && cleanAddr == null) return

        ctx.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE).edit {
            if (cleanName != null) putString(KEY_BT_NAME, cleanName)
            if (cleanAddr != null) putString(KEY_BT_ADDR, cleanAddr)
        }
    }

    private fun setBtTsNow(ctx: Context) {
        ctx.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE).edit {
            putLong(KEY_BT_TS, System.currentTimeMillis())
        }
    }

    private fun btAgeMs(ctx: Context): Long {
        val ts = ctx.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE).getLong(KEY_BT_TS, 0L)
        return if (ts <= 0L) Long.MAX_VALUE else System.currentTimeMillis() - ts
    }

    private fun cachedBtName(ctx: Context) =
        ctx.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE).getString(KEY_BT_NAME, null)

    private fun cachedBtAddr(ctx: Context) =
        ctx.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE).getString(KEY_BT_ADDR, null)

    private fun cachedBtConnected(ctx: Context) =
        ctx.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE).getBoolean(KEY_BT_CONNECTED, false)

    private fun setBtConnected(ctx: Context, connected: Boolean) {
        ctx.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_BT_CONNECTED, connected)
        }
    }

    private fun setLastActivationSource(ctx: Context, source: String) {
        ctx.getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE).edit {
            putString(KEY_LAST_SOURCE, source)
        }
    }

    private fun getLastActivationSource(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE).getString(KEY_LAST_SOURCE, null)

    companion object {
        const val ACTION_TICK = "at.saltyy.switchly.schedule.TICK"
        private const val TAG = "ScheduleReceiver"
        private const val PREFS_WIFI = "switchly_wifi_cache"
        private const val KEY_LAST_SSID = "last_ssid"
        private const val KEY_WIFI_LOG_STATE = "wifi_log_state"
        private const val KEY_WIFI_LOG_AT = "wifi_log_at"
        private const val WIFI_LOG_THROTTLE_MS = 5 * 60 * 1000L
        private const val PREFS_BT = "switchly_bt_cache"
        private const val KEY_BT_NAME = "last_bt_name"
        private const val KEY_BT_ADDR = "last_bt_addr"
        private const val KEY_BT_CONNECTED = "last_bt_connected"
        private const val KEY_BT_TS = "bt_ts"
        private const val BT_FRESHNESS_MS = 90_000L
        private const val PREFS_RUNTIME = "switchly_runtime"
        private const val KEY_LAST_SOURCE = "last_activation_source"
        private const val KEY_LAST_EVAL_LOG_TS = "schedule_eval_log_ts"
        private const val KEY_LAST_EVAL_LOG_SIGNATURE = "schedule_eval_log_signature"
        private const val SCHEDULE_EVAL_LOG_THROTTLE_MS = 15 * 60 * 1000L
        const val EXTRA_LOCATION_SCHEDULE_ID = "locationScheduleId"
        const val EXTRA_LOCATION_TRANSITION = "locationTransition"
        private const val SOURCE_WIFI = "wifi"
        private const val SOURCE_BT = "bt"
        private const val SOURCE_TIME = "time"
        private const val SOURCE_LOCATION = "location"
        private const val LOCATION_DUPLICATE_TRANSITION_WINDOW_MS = 60_000L
        private const val SINGLE_FIRE_WINDOW_MS = 90_000L
    }
}
