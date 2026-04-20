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
import org.json.JSONArray

/**
 * Stores simple Wi-Fi based profile rules like, when connected to SSID X -> activate profile Y.
 */
object WifiRuleStore {
    private const val PREFS = "switchly_wifi_rules"
    private const val KEY_RULES = "rules"

    data class WifiRule(
        val id: Long,
        val ssid: String,
        val profile: String,
        val enabled: Boolean = true,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<WifiRule> {
        val json = prefs(context).getString(KEY_RULES, null) ?: return emptyList()
        val rules = JSONArray(json)
        val result = mutableListOf<WifiRule>()

        for (index in 0 until rules.length()) {
            val item = rules.getJSONObject(index)
            result += WifiRule(
                id = item.getLong("id"),
                ssid = item.getString("ssid"),
                profile = item.getString("profile"),
                enabled = item.optBoolean("enabled", true),
            )
        }

        return result
    }
}
