package at.saltyy.switchly.platform.receiver.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver

/**
 * Lightweight watchdog on unlock/present events.
 * Some OEMs defer alarms in background. 
 * Re-evaluating schedules when the user unlocks helps re-assert the intended state quickly if we are currently inside an active window.
 */
class UserUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val ctx = context.applicationContext

        runCatching {
            ctx.sendBroadcast(
                Intent(ctx, ScheduleReceiver::class.java).apply {
                    this.action = ScheduleReceiver.ACTION_TICK
                    putExtra("time_reason", action)
                    putExtra("alarm_reason", "unlock_watchdog")
                }
            )
        }
    }
}
