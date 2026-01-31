package at.saltyy.switchly.platform.receiver.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver

class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val ctx = context.applicationContext

        Log.d(TAG, "time change event: $action -> replan next alarm")
        runCatching { SchedulePlanner.updateNextAlarm(ctx) }
        runCatching { SchedulePlanner.notifyNextChanged(ctx) }

        // Optional: force immediate re-eval once after time changes
        ctx.sendBroadcast(
            Intent(ctx, ScheduleReceiver::class.java).apply {
                this.action = ScheduleReceiver.ACTION_TICK
                putExtra("time_reason", action)
            }
        )
    }

    companion object {
        private const val TAG = "TimeChangeReceiver"
    }
}
