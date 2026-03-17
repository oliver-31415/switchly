package at.saltyy.switchly.data.prefs

import android.content.Context
import org.json.JSONArray

// Stores simple Wi-Fi based profile rules like, when connected to SSID X -> activate profile Y
object WifiRuleStore {

    private const val PREFS = "switchly_wifi_rules"
    private const val KEY_RULES = "rules"

    data class WifiRule(
        val id: Long,
        val ssid: String,
        val profile: String,
        val enabled: Boolean = true
    )

    fun getAll(context: Context): List<WifiRule> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = sp.getString(KEY_RULES, null) ?: return emptyList()
        val arr = JSONArray(json)
        val out = mutableListOf<WifiRule>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += WifiRule(
                id = o.getLong("id"),
                ssid = o.getString("ssid"),
                profile = o.getString("profile"),
                enabled = o.optBoolean("enabled", true)
            )
        }
        return out
    }

}
