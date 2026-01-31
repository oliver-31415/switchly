package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object ScheduleStore {

    private const val PREFS = "switchly_prefs_schedules"
    private const val KEY_SCHEDULES = "items"

    // Derived flags to avoid repeatedly parsing JSON in hot paths (Wi-Fi / BT retry logic).
    private const val KEY_HAS_WIFI_SCHEDULES = "has_enabled_wifi_schedules"
    private const val KEY_HAS_BT_SCHEDULES = "has_enabled_bt_schedules"

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
        val action: Action = Action.ENABLE
    )

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
        var arrStr = sp.getString(KEY_SCHEDULES, "[]") ?: "[]"

        // Fast path: return cached parse if JSON hasn't changed.
        val cached = cachedJson
        if (cached != null && cached == arrStr) return cachedList

        val arr = JSONArray(arrStr)
        val out = ArrayList<Schedule>()

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)

            val actionStr = o.optString("action", "ENABLE")
            val action = runCatching { Action.valueOf(actionStr) }.getOrElse { Action.ENABLE }

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
            o.put("action", s.action.name)
            arr.put(o)
        }

        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = arr.toString()
        val hasWifi = list.any { it.enabled && !it.wifiSsid.isNullOrBlank() }
        val hasBt = list.any { it.enabled && !it.btDeviceName.isNullOrBlank() }

        sp.edit {
            putString(KEY_SCHEDULES, json)
            putBoolean(KEY_HAS_WIFI_SCHEDULES, hasWifi)
            putBoolean(KEY_HAS_BT_SCHEDULES, hasBt)
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
