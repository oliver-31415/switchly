package at.saltyy.switchly.data.prefs

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver

/**
 * Keeps the app in sync with the current exact-alarm special access state.
 *
 * Android only broadcasts grants for SCHEDULE_EXACT_ALARM, not revocations.
 * We therefore also re-check on app resume and reschedule alarms when the state changes.
 */
object ExactAlarmPermissionSync {

    private const val PREFS = "switchly_schedule_health"
    private const val KEY_LAST_ALLOWED = "exact_alarm_permission_allowed"

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return true
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /**
     * @return true when the stored permission state changed, or when a forced reschedule was run.
     */
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

        if (!changed && !forceReschedule) return false

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
