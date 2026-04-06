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
