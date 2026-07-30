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

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver

/**
 * Keeps the app in sync with the current exact-alarm special access state.
 * Android only broadcasts grants for SCHEDULE_EXACT_ALARM, not revocations.
 * We therefore also re-check on app resume and reschedule alarms when the state changes.
 */
object ExactAlarmPermissionSync {

    private const val PREFS = "switchly_schedule_health"
    private const val KEY_LAST_ALLOWED = "exact_alarm_permission_allowed"

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return true
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    // @return true when the stored permission state changed, or when a forced reschedule was run.
    fun syncAndReschedule(
        context: Context,
        forceReschedule: Boolean = false,
        reason: String = "app_resume"
    ): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentAllowed = canScheduleExactAlarms(appContext)
        val hasStoredState = prefs.contains(KEY_LAST_ALLOWED)
        val previousAllowed = prefs.getBoolean(KEY_LAST_ALLOWED, currentAllowed)
        val changed = !hasStoredState || previousAllowed != currentAllowed

        if (!changed && !forceReschedule) {
            return false
        }

        prefs.edit {
            putBoolean(KEY_LAST_ALLOWED, currentAllowed)
        }

        runCatching { SchedulePlanner.updateNextAlarm(appContext) }
        runCatching { SchedulePlanner.notifyNextChanged(appContext) }
        runCatching {
            appContext.sendBroadcast(
                Intent(appContext, ScheduleReceiver::class.java).apply {
                    action = ScheduleReceiver.ACTION_TICK
                    putExtra("time_reason", reason)
                    putExtra(
                        "alarm_reason",
                        if (currentAllowed) "exact_alarm_permission_granted" else "exact_alarm_permission_revoked"
                    )
                }
            )
        }

        return true
    }
}
