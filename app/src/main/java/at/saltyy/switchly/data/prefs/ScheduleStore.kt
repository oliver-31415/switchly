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

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

object ScheduleStore {

    private const val PREFS = "switchly_prefs_schedules"
    private const val KEY_SCHEDULES = "items"

    // Derived flags to avoid repeatedly parsing JSON in hot paths.
    private const val KEY_HAS_WIFI_SCHEDULES = "has_enabled_wifi_schedules"
    private const val KEY_HAS_BT_SCHEDULES = "has_enabled_bt_schedules"
    private const val KEY_HAS_LOCATION_SCHEDULES = "has_enabled_location_schedules"

    private val cacheLock = Any()
    @Volatile private var cachedJson: String? = null
    @Volatile private var cachedList: List<Schedule> = emptyList()

    enum class Type { WEEKLY, ONE_TIME }

    enum class Action {
        ENABLE,
        DISABLE,
        ENABLE_AND_DISABLE,
        DISABLE_AND_ENABLE,
        TOGGLE
    }

    enum class LocationTrigger {
        ENTER,
        EXIT,
        ENTER_EXIT
    }

    object Days {
        const val MON = 1
        const val TUE = 2
        const val WED = 4
        const val THU = 8
        const val FRI = 16
        const val SAT = 32
        const val SUN = 64

        fun fromCalendarDay(day: Int): Int = when (day) {
            Calendar.MONDAY -> MON
            Calendar.TUESDAY -> TUE
            Calendar.WEDNESDAY -> WED
            Calendar.THURSDAY -> THU
            Calendar.FRIDAY -> FRI
            Calendar.SATURDAY -> SAT
            Calendar.SUNDAY -> SUN
            else -> 0
        }
    }

    data class Schedule(
        val id: Int,
        val enabled: Boolean,
        val profile: String,
        val title: String,
        val note: String,
        val type: Type,
        val daysMask: Int,
        val startMinutes: Int,
        val endMinutes: Int,
        val startDate: Int,
        val endDate: Int,
        val wifiSsid: String? = null,
        val btDeviceName: String? = null,
        val locationLabel: String? = null,
        val locationLat: Double? = null,
        val locationLng: Double? = null,
        val locationRadiusMeters: Int = 250,
        val locationTrigger: LocationTrigger? = null,
        val locationCooldownMinutes: Int = 15,
        val action: Action = Action.ENABLE
    ) {
        fun isLocationSchedule(): Boolean =
            locationLat != null && locationLng != null && locationTrigger != null
    }

    fun todayYmd(): Int {
        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return y * 10000 + m * 100 + d
    }

    fun nextId(items: List<Schedule>): Int {
        var max = 0
        for (s in items) {
            if (s.id > max) max = s.id
        }
        return max + 1
    }

    fun getAll(context: Context): List<Schedule> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arrStr = sp.getString(KEY_SCHEDULES, "[]") ?: "[]"

        val cached = cachedJson
        if (cached != null && cached == arrStr) return cachedList

        val arr = JSONArray(arrStr)
        val out = ArrayList<Schedule>()

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)

            val actionStr = o.optString("action", "ENABLE")
            val action = runCatching { Action.valueOf(actionStr) }.getOrElse { Action.ENABLE }

            val locationTrigger = runCatching {
                o.optString("locationTrigger")
                    .takeIf { it.isNotBlank() }
                    ?.let(LocationTrigger::valueOf)
            }.getOrNull()

            out += Schedule(
                id = o.getInt("id"),
                enabled = o.optBoolean("enabled", true),
                profile = o.optString("profile"),
                title = o.optString("title"),
                note = o.optString("note"),
                type = Type.valueOf(o.optString("type", "WEEKLY")),
                daysMask = o.optInt("daysMask"),
                startMinutes = o.optInt("startMinutes"),
                endMinutes = o.optInt("endMinutes"),
                startDate = o.optInt("startDate"),
                endDate = o.optInt("endDate"),
                wifiSsid = o.optString("wifiSsid").ifBlank { null },
                btDeviceName = o.optString("btDeviceName").ifBlank { null },
                locationLabel = o.optString("locationLabel").ifBlank { null },
                locationLat = if (o.has("locationLat") && !o.isNull("locationLat")) o.optDouble("locationLat") else null,
                locationLng = if (o.has("locationLng") && !o.isNull("locationLng")) o.optDouble("locationLng") else null,
                locationRadiusMeters = o.optInt("locationRadiusMeters", 250).coerceIn(50, 1000),
                locationTrigger = locationTrigger,
                locationCooldownMinutes = o.optInt("locationCooldownMinutes", 15).coerceAtLeast(0),
                action = action
            )
        }

        synchronized(cacheLock) {
            cachedJson = arrStr
            cachedList = out
        }

        return out
    }

    fun hasEnabledWifiSchedules(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.contains(KEY_HAS_WIFI_SCHEDULES)) return sp.getBoolean(KEY_HAS_WIFI_SCHEDULES, false)
        val has = getAll(context).any { it.enabled && !it.wifiSsid.isNullOrBlank() }
        sp.edit { putBoolean(KEY_HAS_WIFI_SCHEDULES, has) }
        return has
    }

    fun hasEnabledBluetoothSchedules(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.contains(KEY_HAS_BT_SCHEDULES)) return sp.getBoolean(KEY_HAS_BT_SCHEDULES, false)
        val has = getAll(context).any { it.enabled && !it.btDeviceName.isNullOrBlank() }
        sp.edit { putBoolean(KEY_HAS_BT_SCHEDULES, has) }
        return has
    }

    fun hasEnabledLocationSchedules(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.contains(KEY_HAS_LOCATION_SCHEDULES)) return sp.getBoolean(KEY_HAS_LOCATION_SCHEDULES, false)
        val has = getAll(context).any { it.enabled && it.isLocationSchedule() }
        sp.edit { putBoolean(KEY_HAS_LOCATION_SCHEDULES, has) }
        return has
    }

    fun saveAll(context: Context, list: List<Schedule>) {
        val arr = JSONArray()
        for (s in list) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("enabled", s.enabled)
            o.put("profile", s.profile)
            o.put("title", s.title)
            o.put("note", s.note)
            o.put("type", s.type.name)
            o.put("daysMask", s.daysMask)
            o.put("startMinutes", s.startMinutes)
            o.put("endMinutes", s.endMinutes)
            o.put("startDate", s.startDate)
            o.put("endDate", s.endDate)
            o.put("wifiSsid", s.wifiSsid ?: "")
            o.put("btDeviceName", s.btDeviceName ?: "")
            o.put("locationLabel", s.locationLabel ?: "")
            if (s.locationLat != null) o.put("locationLat", s.locationLat) else o.put("locationLat", JSONObject.NULL)
            if (s.locationLng != null) o.put("locationLng", s.locationLng) else o.put("locationLng", JSONObject.NULL)
            o.put("locationRadiusMeters", s.locationRadiusMeters)
            o.put("locationTrigger", s.locationTrigger?.name ?: "")
            o.put("locationCooldownMinutes", s.locationCooldownMinutes)
            o.put("action", s.action.name)
            arr.put(o)
        }

        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = arr.toString()
        val hasWifi = list.any { it.enabled && !it.wifiSsid.isNullOrBlank() }
        val hasBt = list.any { it.enabled && !it.btDeviceName.isNullOrBlank() }
        val hasLocation = list.any { it.enabled && it.isLocationSchedule() }

        sp.edit {
            putString(KEY_SCHEDULES, json)
            putBoolean(KEY_HAS_WIFI_SCHEDULES, hasWifi)
            putBoolean(KEY_HAS_BT_SCHEDULES, hasBt)
            putBoolean(KEY_HAS_LOCATION_SCHEDULES, hasLocation)
        }

        synchronized(cacheLock) {
            cachedJson = json
            cachedList = list
        }
    }

    fun pruneExpired(context: Context) {
        val today = todayYmd()
        val all = getAll(context)
        val filtered = all.filter {
            !(it.type == Type.ONE_TIME && it.endDate < today)
        }
        saveAll(context, filtered)
    }
}
