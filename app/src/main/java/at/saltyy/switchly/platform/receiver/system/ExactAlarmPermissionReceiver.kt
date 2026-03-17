package at.saltyy.switchly.platform.receiver.system

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import at.saltyy.switchly.data.prefs.ExactAlarmPermissionSync

/**
 * Android sends this broadcast when SCHEDULE_EXACT_ALARM is granted.
 * Revocations are not broadcast, so the app also re-checks on resume.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (intent?.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return

        ExactAlarmPermissionSync.syncAndReschedule(
            context = context,
            forceReschedule = true,
            reason = "permission_state_changed_broadcast"
        )
    }
}
