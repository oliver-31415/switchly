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
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.ScheduleRuntimeStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.ScheduleExecutionCountStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
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

        val wifiReason = intent.getStringExtra("wifi_reason")
        val alarmReason = intent.getStringExtra("alarm_reason")
        val isTimeBoundaryTick = alarmReason == "time_boundary" || alarmReason == "fallback_repeat"

        // Bluetooth events forwarded by BluetoothConnectionReceiver
        val eventBtName = intent.getStringExtra("eventBtName")
        val eventBtAddr = intent.getStringExtra("eventBtAddr")
        val eventBtConnected = intent.getBooleanExtra("eventBtConnected", false)
        val hasBtEvent = intent.hasExtra("eventBtName") || intent.hasExtra("eventBtAddr") || intent.hasExtra("eventBtConnected")

        if (hasBtEvent) {
            cacheBtEvent(ctx, eventBtName, eventBtAddr, eventBtConnected)
            setBtTsNow(ctx)
        }

        val schedules = ScheduleStore.getAll(ctx).filter { it.enabled }

        // "Trigger" schedules (Wi-Fi/BT based) use wifiSsid/btDeviceName.
        schedules.filter { !it.wifiSsid.isNullOrBlank() }
        schedules.filter { !it.btDeviceName.isNullOrBlank() }

        val needsWifiSsid = schedules.any { !it.wifiSsid.isNullOrBlank() }
        val needsBtInfo = schedules.any { !it.btDeviceName.isNullOrBlank() }

        var ssid = cachedSsid(ctx)

        val btName = cachedBtName(ctx)
        val btAddr = cachedBtAddr(ctx)
        var btConnected = cachedBtConnected(ctx)
        val btAgeMs = btAgeMs(ctx)

        if (needsBtInfo && btConnected && btAgeMs > BT_FRESHNESS_MS && !hasBtEvent) {
            Log.w(TAG, "BT cache stale (${btAgeMs}ms) -> forcing disconnected")
            setBtConnected(ctx, false)
            btConnected = false
        }

        if (needsBtInfo && btConnected && !hasBtEvent) {
            val actuallyConnected = isAnyBtProfileConnected(ctx)
            if (actuallyConnected == false) {
                Log.w(TAG, "System reports no BT profile -> clearing cache")
                setBtConnected(ctx, false)
                btConnected = false
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

        dbg("tick: ssid=$ssid btName=$btName btAddr=$btAddr btConnected=$btConnected btAgeMs=$btAgeMs now=$nowMinutes today=$todayYmd wifiReason=$wifiReason hasBtEvent=$hasBtEvent/$eventBtConnected")

        val lastSource = getLastActivationSource(ctx)
        val hardWifiDisconnect = (wifiReason == "lost") || !wifiConnected

        // Wi-Fi SSID guard
        if (needsWifiSsid && ssid.isNullOrBlank()) {
            if (hardWifiDisconnect) {
                dbg("SSID null and Wi-Fi hard-disconnected -> allow disconnect evaluation")
            } else {
                dbg("Wi-Fi schedules exist but ssid is null while connected -> wait for SSID (reason=$wifiReason)")
                updateNextAlarmAndNotifyIfChanged(ctx)
                return
            }
        }

        // BT name guard
        if (needsBtInfo && btName.isNullOrBlank() && btAddr.isNullOrBlank() && !hasBtEvent) {
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
                !s.wifiSsid.isNullOrBlank() -> {
                    val appliesToday = when (s.type) {
                        ScheduleStore.Type.WEEKLY -> (s.daysMask and todayBit) != 0
                        ScheduleStore.Type.ONE_TIME ->
                            (s.startDate > 0 && s.endDate > 0 && todayYmd in s.startDate..s.endDate)
                    }

                    val timeOk = (s.startMinutes == 0 && s.endMinutes >= 1439) ||
                        inTimeRange(nowMinutes, s.startMinutes, s.endMinutes)

                    val active = appliesToday && timeOk && wifiConnected && ssid.equals(s.wifiSsid, true)
                    if (active) {
                        if (isRangeAction(s.action)) true
                        else shouldFireOneShotConn(ctx, s, token = "WIFI:${s.wifiSsid}:${s.action.name}")
                    } else {
                        ScheduleRuntimeStore.clearLastFiredToken(ctx, s.id)
                        false
                    }
                }

                !s.btDeviceName.isNullOrBlank() -> {
                    val appliesToday = when (s.type) {
                        ScheduleStore.Type.WEEKLY -> (s.daysMask and todayBit) != 0
                        ScheduleStore.Type.ONE_TIME ->
                            (s.startDate > 0 && s.endDate > 0 && todayYmd in s.startDate..s.endDate)
                    }

                    val timeOk = (s.startMinutes == 0 && s.endMinutes >= 1439) ||
                        inTimeRange(nowMinutes, s.startMinutes, s.endMinutes)

                    val active = appliesToday && timeOk && btConnected && btName.equals(s.btDeviceName, true)
                    if (active) {
                        if (isRangeAction(s.action)) true
                        else shouldFireOneShotConn(ctx, s, token = "BT:${s.btDeviceName}:${s.action.name}")
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
            ScheduleRuntimeStore.clearActiveRangeScheduleId(ctx)

            val effectiveLastSource = when {
                previousActiveRange?.wifiSsid?.isNotBlank() == true -> SOURCE_WIFI
                previousActiveRange?.btDeviceName?.isNotBlank() == true -> SOURCE_BT
                previousActiveRange != null -> SOURCE_TIME
                else -> lastSource
            }

            val shouldExitOnce =
                when (effectiveLastSource) {
                    // Wi-Fi/BT schedules can also have time windows now.
                    // If we leave the active range while still connected, we still need to revert.
                    SOURCE_WIFI -> hardWifiDisconnect || isTimeBoundaryTick || alarmReason == "boot_watchdog" || alarmReason == "unlock_watchdog"
                    SOURCE_BT -> (hasBtEvent && !eventBtConnected) || isTimeBoundaryTick || alarmReason == "boot_watchdog" || alarmReason == "unlock_watchdog"
                    SOURCE_TIME -> true
                    else -> false
                }

            if (shouldExitOnce) {
                // ENABLE_AND_DISABLE => disable on exit if we owned enable
                // If a manual override happened during an active range and ownership got
                // cleared by older builds, still revert once on exit.
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
                        (ScheduleRuntimeStore.wasDisabledBySchedule(ctx) || manualOverrideWasActive)
                ) {
                    if (!SwitchModeStore.isBaseEnabled(ctx)) {
                        SwitchModeStore.setEnabledBySchedule(ctx, true)
                        ScheduleRuntimeStore.setDisabledBySchedule(ctx, false)
                    }
                }
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
        dbg("matches=${matches.size} -> winner id=${target.id} profile=${target.profile} start=${target.startMinutes} end=${target.endMinutes} action=${target.action}")
        val source = when {
            !target.wifiSsid.isNullOrBlank() -> SOURCE_WIFI
            !target.btDeviceName.isNullOrBlank() -> SOURCE_BT
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
            val bt = target.btDeviceName ?: ""
            val token = "RANGE_${ymd}_${target.startMinutes}_${target.endMinutes}_${target.action}" + "_${target.profile}_${wifi}_${bt}"
            // Choose the appropriate token gate (time/conn doesn't really matter here)
            shouldFireOneShotTime(ctx, target, token)
        }

        if (executedNow) {
            ScheduleExecutionCountStore.incrementToday(ctx)
        }

        ScheduleRuntimeStore.markExecutedNow(ctx)
        applySchedule(ctx, target, source)
    }

    /**
     * Apply schedule action + profile.
     *
     * IMPORTANT FIX:
     * For range schedules we must set "ownership" even if the baseEnabled state did not change.
     * Otherwise exit-revert can't happen reliably.
     */
    private fun dbg(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    private fun applySchedule(ctx: Context, s: ScheduleStore.Schedule, source: String) {
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
            dbg("Schedule disable blocked by NFC lock (id=${s.id}, source=$source)")
        }

        if (profileChanged) {
            ProfileStore.setCurrent(ctx, s.profile)
        } else if (tempOverrideActive && currentProfile != s.profile) {
            dbg("Temporary override active -> skip schedule profile switch (id=${s.id}, source=$source, target=${s.profile})")
        }

        if (profileChanged || (stateActuallyChanged && baseEnabledAfter)) {
            BlockingRuntime.ensureRunning(ctx)
        }

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
            compareBy<ScheduleStore.Schedule> { activeStartSortKey(it, nowMin) }
                .thenBy { actionPriority(it.action) }
        ) ?: matches.first()
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

    /**
     * Returns the capabilities for any currently connected Wi‑Fi network.
     * We intentionally do NOT use [ConnectivityManager.activeNetwork] because the "default" network can be CELLULAR (or a VPN) while Wi‑Fi is still connected.
     */
    private fun currentWifiCaps(cm: ConnectivityManager): NetworkCapabilities? {
        return try {
            for (n in cm.allNetworks) {
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
                    wifiManager.connectionInfo?.ssid
                }
            } else {
                val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.connectionInfo?.ssid
            } ?: return null

            if (raw == WifiManager.UNKNOWN_SSID) return null
            if (raw.equals("<unknown ssid>", ignoreCase = true)) return null

            raw.trim('"').trim().ifBlank { null }
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun cacheBtEvent(ctx: Context, name: String?, addr: String?, connected: Boolean) {
        ctx.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE).edit {
            putString(KEY_BT_NAME, name?.trim()?.takeIf { it.isNotBlank() })
            putString(KEY_BT_ADDR, addr?.trim()?.takeIf { it.isNotBlank() })
            putBoolean(KEY_BT_CONNECTED, connected)
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
        private const val PREFS_BT = "switchly_bt_cache"
        private const val KEY_BT_NAME = "last_bt_name"
        private const val KEY_BT_ADDR = "last_bt_addr"
        private const val KEY_BT_CONNECTED = "last_bt_connected"
        private const val KEY_BT_TS = "bt_ts"
        private const val BT_FRESHNESS_MS = 90_000L
        private const val PREFS_RUNTIME = "switchly_runtime"
        private const val KEY_LAST_SOURCE = "last_activation_source"
        private const val SOURCE_WIFI = "wifi"
        private const val SOURCE_BT = "bt"
        private const val SOURCE_TIME = "time"
        private const val SINGLE_FIRE_WINDOW_MS = 90_000L
    }
}
