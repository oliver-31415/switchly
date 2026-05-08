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

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

object LocationTriggerMonitor {

    private const val PREFS_SCHEDULES = "switchly_prefs_schedules"
    private const val KEY_SCHEDULES = "items"
    private const val TAG = "LocationTriggerMonitor"
    private const val REQUEST_PREFIX = "switchly_loc_"

    @Volatile private var listening = false
    private var schedulesListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun ensureStarted(context: Context) {
        val ctx = context.applicationContext
        if (!listening) {
            listening = true
            val schedulePrefs = ctx.getSharedPreferences(PREFS_SCHEDULES, Context.MODE_PRIVATE)
            val scheduleL = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_SCHEDULES) syncNow(ctx)
            }
            schedulesListener = scheduleL
            schedulePrefs.registerOnSharedPreferenceChangeListener(scheduleL)
        }
        syncNow(ctx)
    }

    fun syncNow(context: Context) {
        val ctx = context.applicationContext
        val client = LocationServices.getGeofencingClient(ctx)
        val pendingIntent = geofencePendingIntent(ctx)

        runCatching { client.removeGeofences(pendingIntent) }
            .onFailure { Log.w(TAG, "removeGeofences failed: ${it.message}") }

        if (!hasForegroundLocationPermission(ctx)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission(ctx)) return

        val schedules = ScheduleStore.getAll(ctx)
            .filter { it.enabled && it.isLocationSchedule() }
            .take(100)

        if (schedules.isEmpty()) return

        val geofences = schedules.mapNotNull { s ->
            val lat = s.locationLat ?: return@mapNotNull null
            val lng = s.locationLng ?: return@mapNotNull null
            val trigger = s.locationTrigger ?: return@mapNotNull null
            val transitionTypes = when (trigger) {
                ScheduleStore.LocationTrigger.ENTER -> Geofence.GEOFENCE_TRANSITION_ENTER
                ScheduleStore.LocationTrigger.EXIT -> Geofence.GEOFENCE_TRANSITION_EXIT
                ScheduleStore.LocationTrigger.ENTER_EXIT ->
                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
            }

            Geofence.Builder()
                .setRequestId(REQUEST_PREFIX + s.id)
                .setCircularRegion(lat, lng, s.locationRadiusMeters.toFloat().coerceAtLeast(100f))
                .setTransitionTypes(transitionTypes)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()
        }

        if (geofences.isEmpty()) return

        val request = GeofencingRequest.Builder()
            // Important: fire ENTER immediately when a schedule is created while the user is already inside the radius. 
            // Without this, location schedules can look broken until the user leaves and enters the area again.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        if (
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            client.addGeofences(request, pendingIntent)
                .addOnSuccessListener {
                    AppLogStore.append(ctx, "Location", "Geofences registered count=${geofences.size}")
                    checkCurrentLocationForInitialEnter(ctx, schedules)
                }
                .addOnFailureListener { t ->
                    AppLogStore.append(ctx, "Location", "Geofence registration failed: ${t.message ?: t.javaClass.simpleName}")
                    Log.w(TAG, "addGeofences failed async: ${t.message}")
                }
        } catch (se: SecurityException) {
            AppLogStore.append(ctx, "Location", "Geofence registration failed: missing permission")
            Log.w(TAG, "addGeofences failed: missing permission (${se.message})")
        } catch (t: Throwable) {
            AppLogStore.append(ctx, "Location", "Geofence registration failed: ${t.message ?: t.javaClass.simpleName}")
            Log.w(TAG, "addGeofences failed: ${t.message}")
        }
    }

    private fun checkCurrentLocationForInitialEnter(
        context: Context,
        schedules: List<ScheduleStore.Schedule>
    ) {
        if (!hasForegroundLocationPermission(context)) return

        try {
            LocationServices.getFusedLocationProviderClient(context)
                .lastLocation
                .addOnSuccessListener { location ->
                    if (location == null) return@addOnSuccessListener
                    for (s in schedules) {
                        val lat = s.locationLat ?: continue
                        val lng = s.locationLng ?: continue
                        val trigger = s.locationTrigger ?: continue
                        if (trigger != ScheduleStore.LocationTrigger.ENTER &&
                            trigger != ScheduleStore.LocationTrigger.ENTER_EXIT
                        ) continue

                        val configuredRadius = s.locationRadiusMeters.toFloat().coerceAtLeast(100f)
                        // Fused lastLocation can be stale or imprecise after reboot/OEM sleep.
                        // Accept a small accuracy buffer for the initial check only, so users who create a 100m ENTER schedule while already there are not stuck waiting for a new geofence transition.
                        val accuracyBuffer = if (location.hasAccuracy()) location.accuracy.coerceAtMost(75f) else 25f
                        val radius = configuredRadius + accuracyBuffer
                        val distance = FloatArray(1)
                        Location.distanceBetween(
                            location.latitude,
                            location.longitude,
                            lat,
                            lng,
                            distance
                        )

                        if (distance[0] <= radius) {
                            AppLogStore.append(
                                context,
                                "Location",
                                "Initial location inside geofence id=${s.id} distance=${distance[0].toInt()}m radius=${radius.toInt()}m accuracyBuffer=${accuracyBuffer.toInt()}m"
                            )
                            context.sendBroadcast(
                                Intent(context, ScheduleReceiver::class.java).apply {
                                    action = ScheduleReceiver.ACTION_TICK
                                    putExtra(ScheduleReceiver.EXTRA_LOCATION_SCHEDULE_ID, s.id)
                                    putExtra(
                                        ScheduleReceiver.EXTRA_LOCATION_TRANSITION,
                                        Geofence.GEOFENCE_TRANSITION_ENTER
                                    )
                                    putExtra("location_initial_check", true)
                                }
                            )
                        }
                    }
                }
                .addOnFailureListener { t ->
                    AppLogStore.append(context, "Location", "Initial location check failed: ${t.message ?: t.javaClass.simpleName}")
                }
        } catch (se: SecurityException) {
            AppLogStore.append(context, "Location", "Initial location check failed: missing permission")
        } catch (t: Throwable) {
            AppLogStore.append(context, "Location", "Initial location check failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun requestIdForSchedule(scheduleId: Int): String = REQUEST_PREFIX + scheduleId

    fun scheduleIdFromRequestId(requestId: String?): Int? {
        if (requestId.isNullOrBlank() || !requestId.startsWith(REQUEST_PREFIX)) return null
        return requestId.removePrefix(REQUEST_PREFIX).toIntOrNull()
    }

    private fun geofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, LocationGeofenceReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 23006, intent, flags)
    }

    private fun hasForegroundLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
