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

package at.saltyy.switchly.platform.receiver.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class LocationGeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            Log.w(TAG, "Null geofencing event")
            return
        }

        if (event.hasError()) {
            AppLogStore.append(ctx, "Location", "Geofence error code=${event.errorCode}")
            Log.w(TAG, "Geofence error code=${event.errorCode}")
            return
        }

        val transition = event.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT) {
            return
        }

        val geofences = event.triggeringGeofences ?: return
        for (geofence in geofences) {
            val scheduleId = LocationTriggerMonitor.scheduleIdFromRequestId(geofence.requestId) ?: continue
            ctx.sendBroadcast(
                Intent(ctx, ScheduleReceiver::class.java).apply {
                    action = ScheduleReceiver.ACTION_TICK
                    putExtra(ScheduleReceiver.EXTRA_LOCATION_SCHEDULE_ID, scheduleId)
                    putExtra(ScheduleReceiver.EXTRA_LOCATION_TRANSITION, transition)
                    putExtra("location_request_id", geofence.requestId)
                }
            )
        }
    }

    companion object {
        private const val TAG = "LocationGeofenceRx"
    }
}
